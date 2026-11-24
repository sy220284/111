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
    enum class State { Idle, Starting, Live, Stopping }

    var state by mutableStateOf(State.Idle)
        private set
    var error by mutableStateOf("")
        private set
    var muted by mutableStateOf(false)
    var inputLevel by mutableFloatStateOf(0f)
        private set
    var sending by mutableStateOf(false)
        private set
    var userTranscript by mutableStateOf("")
        private set
    var assistantTranscript by mutableStateOf("")
        private set

    private val nativeLock = Mutex()
    private var startup: CompletableDeferred<Unit>? = null
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
        error = ""
        muted = false
        userTranscript = ""
        assistantTranscript = ""
        state = State.Starting
        val ready = CompletableDeferred<Unit>()
        startup = ready
        eventJob = vm.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.bridge.events.collect(::event)
        }
        connectionJob = vm.viewModelScope.launch {
            combine(vm.bridge.status, AppDataTransfer.state) { status, transfer ->
                when {
                    status != EngineStatus.Connected -> "运行引擎已断开"
                    transfer.busy -> "应用数据传输已开始，语音已结束"
                    else -> ""
                }
            }.collect { reason -> if (reason.isNotBlank()) stop(reason) }
        }
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
        state = State.Stopping
        inputLevel = 0f
        startup?.complete(Unit)
        startup = null
        eventJob?.cancel()
        connectionJob?.cancel()
        sendJob?.cancel()
        sending = false
        captureJob?.cancel()
        playbackJob?.cancel()
        uploadJob?.cancel()
        output?.cancel()
        output = null
        vm.viewModelScope.launch {
            try {
                nativeLock.withLock {
                    if (sentStart && vm.bridge.status.value == EngineStatus.Connected) {
                        vm.bridge.request("thread/realtime/stop", thread(), 10_000)
                    }
                }
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                if (error.isBlank()) error = capabilityError(cancel)
            } catch (failure: Exception) {
                if (error.isBlank()) error = capabilityError(failure)
            } finally {
                sentStart = false
                state = State.Idle
            }
        }
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
        val microphone = Channel<JSONObject>(20)
        captureJob = mediaJob {
            try { capture(microphone) } finally { microphone.close() }
        }
        uploadJob = mediaJob {
            for (chunk in microphone) {
                vm.capabilityRequest("thread/realtime/appendAudio", thread().put("audio", chunk), 4_000)
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
    private suspend fun capture(microphone: Channel<JSONObject>) {
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
            while (currentCoroutineContext().isActive) {
                val size = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                check(size >= 0) { "麦克风录音已中断" }
                if (size > 0) {
                    withContext(Dispatchers.Main.immediate) {
                        inputLevel = if (!muted && state == State.Live) pcm16Level(buffer, size) else 0f
                        if (!muted && state == State.Live) {
                            val chunk = JSONObject()
                                .put("data", Base64.encodeToString(buffer, 0, size, Base64.NO_WRAP))
                                .put("sampleRate", rate).put("numChannels", 1).put("samplesPerChannel", size / 2)
                            check(microphone.trySend(chunk).isSuccess) { "语音网络延迟过高，请重新连接" }
                        }
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
                    track.play()
                    sampleRate = rate
                    channels = count
                }
                val bytes = Base64.decode(audio.getString("data"), Base64.DEFAULT)
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "设备语音播放已中断" }
                    offset += written
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
