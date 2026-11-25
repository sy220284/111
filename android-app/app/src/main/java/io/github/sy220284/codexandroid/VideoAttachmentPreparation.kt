package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/** Locally samples changes, preserves chronological coverage, and remuxes synchronized AAC clips. */
internal object VideoAttachmentPreparation {
    private const val MAX_AUDIO_BYTES = 50L * 1024 * 1024
    const val SHEET_WIDTH = 1440
    private const val TILE_WIDTH = 480
    private const val TILE_HEIGHT = 270
    private const val LABEL_HEIGHT = 32
    private val codecLock = Any()

    // Only one heavy media decoder at a time; photo/audio imports may still proceed in parallel.
    fun prepare(source: File, checkActive: () -> Unit = {}): File = synchronized(codecLock) {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        val generated = mutableListOf<File>()
        try {
            checkActive()
            retriever.setDataSource(source.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("无法读取视频时长")
            val windows = VideoSamplingPlan.windows(duration)
            require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "文件不含可读取的视频轨" }
            extractor.setDataSource(source.absolutePath)
            val audioTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            require(audioTracks.size <= 1) { "多音轨视频请先选择并导出所需音轨" }
            val track = audioTracks.singleOrNull()
            if (track != null) require(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
                "视频音轨须为 AAC；请先转为 AAC 编码的 MP4 或分别上传音频和无声视频"
            }
            val output = File(source.parentFile, source.nameWithoutExtension + (if (track == null) ".silent.mp4" else ".av.mp4"))
            require(!output.exists()) { "视频附件已存在" }

            var previous: IntArray? = null
            val probes = VideoSamplingPlan.probeTimes(duration).map { time ->
                checkActive()
                val frame = frameAt(retriever, time, 96, 96)
                try {
                    val normalized = Bitmap.createScaledBitmap(frame, 96, 54, true)
                    try {
                        val pixels = IntArray(96 * 54)
                        normalized.getPixels(pixels, 0, 96, 0, 0, 96, 54)
                        VideoSamplingPlan.Probe(time, previous?.let { VideoSamplingPlan.change(it, pixels) } ?: 0.0)
                            .also { previous = pixels }
                    } finally { if (normalized !== frame) normalized.recycle() }
                } finally { frame.recycle() }
            }
            val plan = VideoSamplingPlan.plan(duration, probes)
            plan.forEachIndexed { index, segment ->
                checkActive()
                val sheet = File(VideoTimeline.sheet(output.path, index)).also(generated::add)
                renderSheet(retriever, segment, sheet, checkActive)
            }
            val audioStarts = if (track == null) emptyMap() else
                extractAudio(extractor, track, windows, output.path, generated, checkActive)
            val timeline = VideoTimeline(duration, plan.mapIndexed { i, segment ->
                VideoTimeline.Segment(segment.window.startMs, segment.window.endMs, segment.times, audioStarts[i])
            })
            val manifest = File(VideoTimeline.manifest(output.path)).also(generated::add)
            manifest.writeText(timeline.encode())
            checkActive()
            check(source.renameTo(output)) { "无法保存原始视频" }
            output
        } catch (failure: Throwable) {
            for (file in generated) if (file.exists() && !file.delete())
                failure.addSuppressed(IllegalStateException("无法清理视频解析文件：${file.name}"))
            throw failure
        } finally {
            try { retriever.release() } finally { extractor.release() }
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, time: Long, width: Int, height: Int): Bitmap =
        retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
            ?: error("无法提取视频采样画面（${time}ms）")

    private fun renderSheet(retriever: MediaMetadataRetriever, segment: VideoSamplingPlan.Segment,
                            target: File, checkActive: () -> Unit) {
        val rows = (segment.times.size + 2) / 3
        val sheet = Bitmap.createBitmap(SHEET_WIDTH, rows * (TILE_HEIGHT + LABEL_HEIGHT), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.WHITE; textSize = 22f
            }
            segment.times.forEachIndexed { index, time ->
                checkActive()
                val frame = frameAt(retriever, time, TILE_WIDTH, TILE_HEIGHT)
                try {
                    val x = index % 3 * TILE_WIDTH
                    val y = index / 3 * (TILE_HEIGHT + LABEL_HEIGHT)
                    val scale = minOf(TILE_WIDTH.toFloat() / frame.width, TILE_HEIGHT.toFloat() / frame.height)
                    val w = (frame.width * scale).toInt().coerceAtLeast(1)
                    val h = (frame.height * scale).toInt().coerceAtLeast(1)
                    val left = x + (TILE_WIDTH - w) / 2
                    val top = y + (TILE_HEIGHT - h) / 2
                    canvas.drawBitmap(frame, null, Rect(left, top, left + w, top + h), paint)
                    canvas.drawText(String.format(Locale.ROOT, "~%02d:%02d.%03d", time / 60000, time / 1000 % 60, time % 1000),
                        x + 12f, y + TILE_HEIGHT + 24f, paint)
                } finally { frame.recycle() }
            }
            target.outputStream().use { check(sheet.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "无法保存视频分段画面" } }
        } finally { sheet.recycle() }
    }

    /** One pass over the original track. Retain original sample times as per-clip timeline offsets. */
    private fun extractAudio(extractor: MediaExtractor, track: Int, windows: List<VideoSamplingPlan.Window>,
                             path: String, generated: MutableList<File>, checkActive: () -> Unit): Map<Int, Long> {
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        val offsets = linkedMapOf<Int, Long>()
        var muxer: MediaMuxer? = null
        var segment = -1
        var outputTrack = -1
        var bytes = 0L
        fun finish() {
            val current = muxer ?: return
            muxer = null
            try { current.stop() } finally { current.release() }
        }
        try {
            while (true) {
                checkActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size > 0 && size <= buffer.capacity()) { "视频音频样本大小无效" }
                val time = extractor.sampleTime
                require(time >= 0L) { "视频音频时间戳无效" }
                val index = (time / (VideoSamplingPlan.SEGMENT_MS * 1000)).toInt().coerceAtMost(windows.lastIndex)
                require(time < windows[index].endMs * 1000 || (index == windows.lastIndex && index == segment && time <= windows[index].endMs * 1000 + 250_000L)) { "音轨超出视频时间轴，请重新编码音视频" }
                if (index != segment) {
                    require(index > segment) { "音轨时间戳无序" }
                    finish()
                    segment = index
                    val target = File(VideoTimeline.audio(path, index)).also(generated::add)
                    val next = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = next
                    outputTrack = next.addTrack(format)
                    next.start()
                    offsets[index] = time
                }
                bytes += size
                require(bytes <= MAX_AUDIO_BYTES - 1024 * 1024) { "视频音轨总量超过50 MB" }
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "加密音轨无法读取" }
                info.set(0, size, time - offsets.getValue(segment),
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                checkNotNull(muxer).writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            require(offsets.isNotEmpty()) { "视频音轨为空" }
            finish()
            require(offsets.keys.sumOf { File(VideoTimeline.audio(path, it)).length() } in 1..MAX_AUDIO_BYTES) { "视频音轨总量无效" }
            return offsets
        } finally { muxer?.release() }
    }
}
