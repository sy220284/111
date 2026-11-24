package io.github.sy220284.codexandroid

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class RealtimeVoiceController(private val vm: CodexViewModel, private val threadId: String) {
    enum class State { Idle, Starting, Live, Stopping, StopFailed }

    var state by mutableStateOf(State.Idle)
        private set
    var error by mutableStateOf("")
        private set
    private var muteState by mutableStateOf(false)
    private val uploadGate = VoiceUploadGate()
    private var microphoneQueue: Channel<VoicePacket>? = null
    private data class VoicePacket(val revision: Long, val audio: JSONObject)
    var muted: Boolean
        get() = muteState
        set(value) {
            if (muteState != value) {
                muteState = value
                uploadGate.setMuted(value)
                if (value) {
                    inputLevel = 0f
                    microphoneQueue?.let { queue ->
                        while (queue.tryReceive().isSuccess) { /* discard unsent audio */ }
                    }
                }
            }
        }
    var inputLevel by mutableFloatStateOf(0f)
        private set
    var sending by mutableStateOf(false)
        private set
    var userTranscript by mutableStateOf("")
        private set
    var assistantTranscript by mutableStateOf("")
        private set

    // A controller can be recreated when navigation or a thread switch disposes the UI.
    // The engine/microphone lease survives that disposal until stop and media cleanup finish.
    private val lease = vm.realtimeVoiceLease
    private val nativeLock = Mutex()
    private var startup: CompletableDeferred<Unit>? = null
    private var closed = CompletableDeferred<Unit>()
    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var uploadJob: Job? = null
    private var sendJob: Job? = null
    private var output: Channel<JSONObject>? = null
    private var sentStart = false

    private fun thread() = JSONObject().put("threadId", threadId)

    fun start(voice: String) {
        if (state != State.Idle) return
        if (vm.bridge.status.value != EngineStatus.Connected || !vm.globalWorkSnapshot().canStartNewTask ||
            vm.connectionRestarter.busy.value || vm.activeThread.value != threadId || vm.threadSwitching.value) {
            error = "当前运行状态无法启动实时语音，请完成其他操作后重试"
            return
        }
        if (!lease.acquire(this)) {
            error = "上一段语音仍在结束，请稍后重试"
            return
        }
        error = ""
        muted = false
        userTranscript = ""
        assistantTranscript = ""
        state = State.Starting
        val ready = CompletableDeferred<Unit>()
        closed = CompletableDeferred()
        startup = ready
        eventJob = vm.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.bridge.events.collect(::event)
        }
        connectionJob = vm.viewModelScope.launch(start = CoroutineStart.LAZY) {
            combine(vm.bridge.status, AppDataTransfer.state, vm.activeThread, vm.threadSwitching) {
                    status, transfer, active, switching ->
                when {
                    status != EngineStatus.Connected -> "运行引擎已断开"
                    transfer.busy -> "应用数据传输已开始，语音已结束"
                    active != threadId || switching -> "会话已切换，语音已结束"
                    else -> ""
                }
            }.collect { reason ->
                if (vm.bridge.status.value != EngineStatus.Connected) closed.complete(Unit)
                if (reason.isNotBlank()) stop(reason)
            }
        }
        connectionJob?.start()
        vm.viewModelScope.launch {
            try {
                nativeLock.withLock {
                    if (state != State.Starting) return@withLock
                    check(vm.activeThread.value == threadId && !vm.threadSwitching.value) { "当前会话已切换" }
                    val params = thread().put("outputModality", "audio")
                        .put("transport", JSONObject().put("type", "websocket"))
                    if (voice.isNotBlank()) params.put("voice", voice)
                    sentStart = true
                    vm.capabilityRequest("thread/realtime/start", params, 15_000)
                    withTimeout(15_000) { ready.await() }
                    if (state == State.Starting) {
                        state = State.Live
                        startMedia()
                    }
                }
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                stop(capabilityError(cancel))
            } catch (failure: Exception) {
                stop(capabilityError(failure))
            }
        }
    }

    fun stop(reason: String = "") {
        if (reason.isNotBlank()) error = reason
        if (state == State.Idle || state == State.Stopping) return
        lease.clearFailure(this)
        state = State.Stopping
        uploadGate.invalidate()
        inputLevel = 0f
        startup?.complete(Unit)
        startup = null
        // Keep observing the native closed event and connection until cleanup is proven.
        val media = listOfNotNull(sendJob, captureJob, playbackJob, uploadJob)
        media.forEach { it.cancel() }
        sending = false
        output?.cancel()
        output = null
        microphoneQueue?.cancel()
        microphoneQueue = null
        vm.viewModelScope.launch {
            try {
                media.joinAll()
                nativeLock.withLock {
                    if (sentStart && !closed.isCompleted) {
                        if (vm.bridge.status.value == EngineStatus.Connected) {
                            try {
                                vm.bridge.request("thread/realtime/stop", thread(), 10_000)
                            } catch (failure: Exception) {
                                if (!closed.isCompleted) throw failure
                            }
                            // Rust acknowledges submission; the closed notification proves completion.
                            withTimeout(10_000) { closed.await() }
                        } else closed.complete(Unit)
                    }
                }
                finishStop()
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                failStop(cancel)
            } catch (failure: Exception) {
                failStop(failure)
            }
        }
    }

    private fun finishStop() {
        eventJob?.cancel()
        connectionJob?.cancel()
        sentStart = false
        state = State.Idle
        lease.release(this)
    }

    private fun failStop(failure: Exception) {
        // Closure may have arrived concurrently with an RPC failure.
        if (closed.isCompleted) {
            finishStop()
            return
        }
        error = capabilityError(failure)
        state = State.StopFailed
        lease.markFailed(this, error) { stop() }
    }

    fun sendText(text: String, asSpeech: Boolean, onSent: () -> Unit) {
        if (state != State.Live || sendJob?.isActive == true || text.isBlank()) return
        error = ""
        sending = true
        sendJob = vm.viewModelScope.launch {
            try {
                if (asSpeech) {
                    vm.capabilityRequest("thread/realtime/appendSpeech", thread().put("text", text), 8_000)
                } else {
                    vm.capabilityRequest("thread/realtime/appendText",
                        thread().put("text", text).put("role", "user"), 8_000)
                }
                onSent()
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                error = capabilityError(cancel)
            } catch (failure: Exception) {
                error = capabilityError(failure)
            } finally {
                sending = false
            }
        }
    }

    private fun event(message: JSONObject) {
        val params = message.optJSONObject("params") ?: return
        if (params.value("threadId") != threadId) return
        when (message.value("method")) {
            "thread/realtime/started" -> startup?.complete(Unit)
            "thread/realtime/closed" -> {
                closed.complete(Unit)
                sentStart = false
                stop()
            }
            "thread/realtime/error" -> stop(params.value("message").ifBlank { "实时语音连接中断" })
            "thread/realtime/outputAudio/delta" -> if (state == State.Live) {
                params.optJSONObject("audio")?.let {
                    if (output?.trySend(it)?.isFailure == true) stop("语音播放积压，请重新连接")
                }
            }
            "thread/realtime/transcript/delta" -> if (state == State.Live) {
                val text = params.value("delta")
                when (params.value("role")) {
                    "user" -> userTranscript = (userTranscript + text).takeLast(2_000)
                    "assistant" -> assistantTranscript = (assistantTranscript + text).takeLast(2_000)
                }
            }
            "thread/realtime/transcript/done" -> if (state == State.Live) {
                val text = params.value("text").takeLast(2_000)
                when (params.value("role")) {
                    "user" -> userTranscript = text
                    "assistant" -> assistantTranscript = text
                }
            }
        }
    }

    private fun startMedia() {
        val queue = Channel<JSONObject>(64)
        output = queue
        val microphone = Channel<VoicePacket>(20)
        microphoneQueue = microphone
        captureJob = mediaJob {
            try { capture(microphone) } finally { microphone.close() }
        }
        uploadJob = mediaJob {
            for (packet in microphone) {
                // Serialize admission with mute and stop on the main dispatcher.
                // A request already sent to the service cannot be recalled.
                withContext(Dispatchers.Main.immediate) {
                    if (state == State.Live && uploadGate.accepts(packet.revision)) {
                        try {
                            vm.capabilityRequest("thread/realtime/appendAudio",
                                thread().put("audio", packet.audio), 4_000,
                                sendPermit = { uploadGate.accepts(packet.revision) })
                        } catch (_: RpcWriteRevoked) {
                            // Mute or stop revoked this queued packet at the actual writer.
                        }
                    }
                }
            }
        }
        playbackJob = mediaJob { play(queue) }
    }

    private fun mediaJob(block: suspend () -> Unit): Job = vm.viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (cancel: CancellationException) {
            if (cancel !is TimeoutCancellationException) throw cancel
            stop(capabilityError(cancel))
        } catch (failure: Exception) {
            stop(capabilityError(failure))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(microphone: Channel<VoicePacket>) {
        val rate = 24_000
        val channels = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(rate, channels, format)
        check(minimum > 0) { "此设备不支持实时语音的录音格式" }
        val buffer = ByteArray(rate * 2 / 20)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate, channels, format, maxOf(minimum * 4, buffer.size * 8))
        var echo: AcousticEchoCanceler? = null
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "无法打开麦克风" }
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(record.audioSessionId)
                echo?.enabled = true
            }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未能启动" }
            var filled = 0
            var revision = withContext(Dispatchers.Main.immediate) { uploadGate.revision }
            while (currentCoroutineContext().isActive) {
                val size = record.read(buffer, filled, buffer.size - filled, AudioRecord.READ_NON_BLOCKING)
                check(size >= 0) { "麦克风录音已中断" }
                if (size == 0) {
                    delay(10)
                    continue
                }
                filled += size
                withContext(Dispatchers.Main.immediate) {
                    if (muted || state != State.Live || revision != uploadGate.revision) {
                        // Never combine samples collected across a mute/unmute transition.
                        revision = uploadGate.revision
                        filled = 0
                        inputLevel = 0f
                    } else if (filled == buffer.size) {
                        // Preserve the original 50 ms wire packet size, even when
                        // non-blocking Android reads return only a partial buffer.
                        inputLevel = pcm16Level(buffer, filled)
                        val chunk = JSONObject()
                            .put("data", Base64.encodeToString(buffer, 0, filled, Base64.NO_WRAP))
                            .put("sampleRate", rate).put("numChannels", 1).put("samplesPerChannel", filled / 2)
                        check(microphone.trySend(VoicePacket(revision, chunk)).isSuccess) { "语音网络延迟过高，请重新连接" }
                        filled = 0
                    }
                }
            }
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } finally {
                echo?.release()
                record.release()
            }
        }
    }

    private suspend fun play(queue: Channel<JSONObject>) {
        var track: AudioTrack? = null
        var sampleRate = 0
        var channels = 0
        try {
            for (audio in queue) {
                val rate = audio.getInt("sampleRate")
                val count = audio.getInt("numChannels")
                check(rate in 8_000..96_000 && count in 1..2) { "收到不支持的语音播放格式" }
                if (track == null || rate != sampleRate || count != channels) {
                    track?.release()
                    track = null
                    val mask = if (count == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0) { "设备无法播放当前语音格式" }
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setChannelMask(mask).setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(minimum).setTransferMode(AudioTrack.MODE_STREAM).build()
                    check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化语音播放设备" }
                    track.play()
                    sampleRate = rate
                    channels = count
                }
                val bytes = Base64.decode(audio.getString("data"), Base64.DEFAULT)
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0) { "设备语音播放已中断" }
                    if (written == 0) delay(10) else offset += written
                }
            }
        } finally {
            track?.release()
        }
    }
}

/** Normalized RMS from the actual signed little-endian PCM samples. */
internal fun pcm16Level(bytes: ByteArray, size: Int): Float {
    val count = size.coerceIn(0, bytes.size) / 2
    if (count == 0) return 0f
    var squares = 0.0
    for (i in 0 until count) {
        val sample = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
        squares += sample * sample
    }
    return (kotlin.math.sqrt(squares / count) * 3.0).coerceIn(0.0, 1.0).toFloat()
}
