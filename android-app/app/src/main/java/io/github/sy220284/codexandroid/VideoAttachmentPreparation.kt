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

/** Explicit sampled-video representation using only native image/audio inputs. */
internal object VideoAttachmentPreparation {
    private const val MAX_DURATION_MS = 600_000L
    private const val MAX_AUDIO_BYTES = 50L * 1024 * 1024
    fun prepare(source: File): File {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var destination: File? = null
        try {
            retriever.setDataSource(source.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("无法读取视频时长")
            require(duration in 1..MAX_DURATION_MS) { "视频时长上限10分钟" }
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
            destination = output
            val sheet = Bitmap.createBitmap(1280, 1170, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(sheet)
                canvas.drawColor(Color.BLACK)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE; textSize = 24f }
                repeat(6) { index ->
                    val time = duration * (2L * index + 1) / 12
                    val frame = retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 640, 360)
                        ?: error("无法提取视频第 ${index + 1} 个采样画面")
                    try {
                        val x = index % 2 * 640
                        val y = index / 2 * 390
                        val scale = minOf(640f / frame.width, 360f / frame.height)
                        val w = (frame.width * scale).toInt()
                        val h = (frame.height * scale).toInt()
                        val left = x + (640 - w) / 2
                        val top = y + (360 - h) / 2
                        canvas.drawBitmap(frame, null, Rect(left, top, left + w, top + h), paint)
                        canvas.drawText(String.format(Locale.ROOT, "%02d:%02d.%03d", time / 60000, time / 1000 % 60, time % 1000), x + 12f, y + 384f, paint)
                    } finally { frame.recycle() }
                }
                File(LocalMediaAttachmentPolicy.frames(output.path)).outputStream().use {
                    check(sheet.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "无法保存视频采样画面" }
                }
            } finally { sheet.recycle() }
            if (track != null) extractAudio(extractor, track, File(LocalMediaAttachmentPolicy.sound(output.path)))
            check(source.renameTo(output)) { "无法保存原始视频" }
            return output
        } catch (failure: Exception) {
            destination?.let { output ->
                LocalMediaAttachmentPolicy.ownedPaths(output.path).forEach { path ->
                    val file = File(path)
                    if (file.exists() && !file.delete()) failure.addSuppressed(IllegalStateException("无法清理视频解析文件"))
                }
            }
            throw failure
        } finally {
            try { retriever.release() } finally { extractor.release() }
        }
    }

    private fun extractAudio(extractor: MediaExtractor, track: Int, target: File) {
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var total = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                total += size
                require(total <= MAX_AUDIO_BYTES - 1024 * 1024) { "视频音轨超过50 MB" }
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "加密音轨无法读取" }
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            require(total > 0) { "视频音轨为空" }
            muxer.stop()
            require(target.length() in 1..MAX_AUDIO_BYTES) { "视频音轨大小无效" }
        } finally { muxer.release() }
    }
}
