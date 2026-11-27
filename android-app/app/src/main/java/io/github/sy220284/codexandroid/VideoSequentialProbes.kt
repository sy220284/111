package io.github.sy220284.codexandroid

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock

/** One hardware decode pass for dense low-resolution scene probes. Full-quality
 * selected frames still use the native retriever and original timestamps. */
internal object VideoSequentialProbes {
    private const val WIDTH = 96
    private const val HEIGHT = 54
    data class Decoder(val track: Int, val name: String, val durationUs: Long)

    private fun videoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: -1

    /** Only flexible SDR AVC/HEVC profiles with a proven decoder capability use this route. */
    fun decoder(path: String, hardwareOnly: Boolean = true): Decoder? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val track = videoTrack(extractor)
            if (track < 0) return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (mime !in setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) return null
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0 || width.toLong() * height > 1920L * 1080) return null
            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0)
            if (transfer != 0 && transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO) return null
            val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0)
            if (standard !in setOf(0, MediaFormat.COLOR_STANDARD_BT601_NTSC,
                    MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT709)) return null
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && (!hardwareOnly || info.isHardwareAccelerated) && mime in info.supportedTypes &&
                    info.getCapabilitiesForType(mime).let { capabilities ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats &&
                            capabilities.isFormatSupported(format)
                    }
            } ?: return null
            return Decoder(track, codec.name, format.getLong(MediaFormat.KEY_DURATION))
        } finally { extractor.release() }
    }

    fun select(path: String, times: List<Long>, checkActive: () -> Unit): Decoder? {
        val codec = decoder(path) ?: return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            extractor.selectTrack(codec.track)
            val gops = ArrayList<VideoSamplingPlan.Gop>()
            var start = -1L
            var count = 0
            var total = 0
            while (extractor.sampleTime >= 0) {
                if (total++ % 256 == 0) checkActive()
                // Bound the planning cost for exotic high-frame-rate streams.
                if (total > 100000) return null
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    if (count > 0) gops.add(VideoSamplingPlan.Gop(start, count))
                    start = extractor.sampleTime
                    count = 0
                }
                if (start < 0) return null
                count++
                if (!extractor.advance()) break
            }
            if (count > 0) gops.add(VideoSamplingPlan.Gop(start, count))
            return codec.takeIf { VideoSamplingPlan.preferSequential(gops, times, it.durationUs) }
        } finally { extractor.release() }
    }

    fun read(path: String, decoder: Decoder, times: List<Long>, checkActive: () -> Unit): List<VideoSamplingPlan.Probe> {
        require(times.isNotEmpty() && times.zipWithNext().all { (a, b) -> a < b })
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            extractor.selectTrack(decoder.track)
            val format = extractor.getTrackFormat(decoder.track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val active = MediaCodec.createByCodecName(decoder.name)
            codec = active
            active.configure(format, null, null, 0)
            active.start()
            val info = MediaCodec.BufferInfo()
            val result = ArrayList<VideoSamplingPlan.Probe>(times.size)
            var previous = IntArray(WIDTH * HEIGHT)
            var current = IntArray(previous.size)
            val lastProbe = IntArray(previous.size)
            var lastTime: Long? = null
            var hasProbe = false
            var inputEnded = false
            var outputEnded = false
            var progress = SystemClock.elapsedRealtime()
            var color = format
            fun emit(pixels: IntArray) {
                val change = if (hasProbe) VideoSamplingPlan.change(lastProbe, pixels) else 0.0
                result.add(VideoSamplingPlan.Probe(times[result.size], change))
                pixels.copyInto(lastProbe)
                hasProbe = true
            }
            while (!outputEnded && result.size < times.size) {
                checkActive()
                if (!inputEnded) {
                    val index = active.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = checkNotNull(active.getInputBuffer(index))
                        input.clear()
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) {
                            active.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
                            active.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progress = SystemClock.elapsedRealtime()
                    }
                }
                val out = active.dequeueOutputBuffer(info, 10000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) color = active.outputFormat
                else if (out >= 0) {
                    try {
                        if (info.size > 0 && info.presentationTimeUs >= 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val time = info.presentationTimeUs
                            val prior = lastTime
                            require(prior == null || time >= prior) { "视频解码时间戳无序" }
                            checkNotNull(active.getOutputImage(out)) { "视频解码器未提供 YUV 画面" }.use {
                                sample(it, color, current)
                            }
                            while (result.size < times.size && times[result.size] * 1000 <= time) {
                                val target = times[result.size] * 1000
                                emit(if (prior != null && target - prior <= time - target) previous else current)
                            }
                            val swap = previous
                            previous = current
                            current = swap
                            lastTime = time
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { active.releaseOutputBuffer(out, false) }
                    progress = SystemClock.elapsedRealtime()
                }
                check(SystemClock.elapsedRealtime() - progress < 15000) { "视频解码长时间无进展" }
            }
            require(lastTime != null) { "视频没有可用画面" }
            while (result.size < times.size) { checkActive(); emit(previous) }
            return result
        } finally {
            try { codec?.release() } finally { extractor.release() }
        }
    }

    /** Read only the probe grid; flexible YUV row/pixel strides and crop are
     * honored without allocating a full-resolution RGB bitmap per frame. */
    private fun sample(image: Image, format: MediaFormat, output: IntArray) {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "视频色彩格式不支持" }
        val crop = image.cropRect
        require(crop.width() > 0 && crop.height() > 0)
        val planes = image.planes
        val buffers = planes.map { it.buffer }
        val offsets = buffers.map { it.position() }
        val full = format.getInteger(MediaFormat.KEY_COLOR_RANGE, 0) == MediaFormat.COLOR_RANGE_FULL
        val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0)
        val hd = standard == MediaFormat.COLOR_STANDARD_BT709 || (standard == 0 && crop.width() >= 1280)
        fun get(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            return buffers[plane].get(offsets[plane] + y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        for (row in 0 until HEIGHT) for (col in 0 until WIDTH) {
            val x = crop.left + ((2 * col + 1) * crop.width()) / (2 * WIDTH)
            val y = crop.top + ((2 * row + 1) * crop.height()) / (2 * HEIGHT)
            val luminance = get(0, x, y)
            val u = get(1, x / 2, y / 2) - 128
            val v = get(2, x / 2, y / 2) - 128
            val l = if (full) luminance * 256 else (luminance - 16).coerceAtLeast(0) * 298
            val red = (l + v * (if (full) if (hd) 403 else 359 else if (hd) 459 else 409) + 128) shr 8
            val green = (l - u * (if (full) if (hd) 48 else 88 else if (hd) 55 else 100) -
                v * (if (full) if (hd) 120 else 183 else if (hd) 136 else 208) + 128) shr 8
            val blue = (l + u * (if (full) if (hd) 475 else 454 else if (hd) 541 else 516) + 128) shr 8
            output[row * WIDTH + col] = (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
        }
    }
}
