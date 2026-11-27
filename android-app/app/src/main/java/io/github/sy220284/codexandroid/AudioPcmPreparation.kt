package io.github.sy220284.codexandroid

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Decode the real container, downmix and resample to documented 16 kHz PCM WAV before ASR. */
internal object AudioPcmPreparation {
    private const val RATE = 16_000
    private const val LIMIT = 180 * RATE
    fun clips(source: File, directory: File, checkActive: () -> Unit): List<File> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var out: RandomAccessFile? = null
        val files = mutableListOf<File>()
        var samples = 0
        val pending = ByteArray(8192)
        var pendingSize = 0
        fun flush() {
            if (pendingSize > 0) { out!!.write(pending, 0, pendingSize); pendingSize = 0 }
        }
        fun finish() {
            val current = out ?: return
            flush()
            current.seek(0)
            val bytes = samples * 2
            fun word(n: Int) { current.write(n and 255); current.write(n ushr 8 and 255) }
            fun int(n: Int) { word(n); word(n ushr 16) }
            current.writeBytes("RIFF"); int(36 + bytes); current.writeBytes("WAVEfmt ")
            int(16); word(1); word(1); int(RATE); int(RATE * 2); word(2); word(16)
            current.writeBytes("data"); int(bytes); current.fd.sync(); current.close(); out = null
        }
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputEnded = false
            var ended = false
            var frameNumber = 0L
            var emitted = 0L
            val info = MediaCodec.BufferInfo()
            while (!ended) {
                checkActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        inputEnded = size < 0
                        decoder.queueInputBuffer(index, 0, maxOf(0, size),
                            if (inputEnded) 0 else extractor.sampleTime,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat
                    rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(!decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                        decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "音频解码输出不支持16位PCM" }
                    require(rate > 0 && channels in 1..8)
                } else if (index >= 0) {
                    try {
                        val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        while (buffer.remaining() >= channels * 2) {
                            var sum = 0
                            repeat(channels) { sum += buffer.short.toInt() }
                            frameNumber++
                            while (emitted * rate < frameNumber * RATE) {
                                require(emitted < 300L * RATE) { "单段转录音频超过5分钟" }
                                if (out == null) {
                                    val file = File.createTempFile("asr-", ".wav", directory)
                                    files += file; out = RandomAccessFile(file, "rw").apply { setLength(44); seek(44) }
                                    samples = 0
                                }
                                val value = sum / channels
                                pending[pendingSize++] = value.toByte(); pending[pendingSize++] = (value ushr 8).toByte()
                                if (pendingSize == pending.size) flush()
                                samples++; emitted++
                                if (samples == LIMIT) finish()
                            }
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            finish()
            require(files.isNotEmpty()) { "音频没有可转录样本" }
            return files
        } catch (failure: Throwable) {
            out?.close(); out = null
            files.forEach { if (it.exists() && !it.delete()) failure.addSuppressed(IllegalStateException("无法清理转录音频")) }
            throw failure
        } finally { out?.close(); try { codec?.release() } finally { extractor.release() } }
    }
}
