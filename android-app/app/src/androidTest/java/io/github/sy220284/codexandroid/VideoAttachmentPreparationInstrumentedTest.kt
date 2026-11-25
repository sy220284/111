package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoAttachmentPreparationInstrumentedTest {
    @Test fun extractsActualMp4FramesAndAacAudioAndPreservesSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "media-test-" + java.util.UUID.randomUUID())
        check(directory.mkdirs())
        try {
            val bytes = instrumentation.context.assets.open("media/blue-aac.mp4.b64").use {
                Base64.decode(it.readBytes(), Base64.DEFAULT)
            }
            assertEquals("4e4ed97b4bf73b0d23818873698ce32d29fee6d66a16a1ca169a8b5e01741ec1", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            val source = File(directory, "source.mp4").apply { writeBytes(bytes) }
            val prepared = VideoAttachmentPreparation.prepare(source)
            assertTrue(prepared.name.endsWith(".av.mp4"))
            assertArrayEquals(bytes, prepared.readBytes())
            val bitmap = BitmapFactory.decodeFile(LocalMediaAttachmentPolicy.frames(prepared.path))
            assertNotNull(bitmap)
            assertEquals(VideoAttachmentPreparation.SHEET_WIDTH, bitmap.width)
            val timeline = VideoTimeline.read(prepared.path)
            assertEquals(1, timeline.segments.size)
            assertTrue(timeline.durationMs in 1900..2100)
            assertEquals(0L, timeline.segments.first().startMs)
            assertTrue(timeline.segments.first().times.size >= 3)
            bitmap.recycle()
            val audio = MediaExtractor()
            try {
                audio.setDataSource(VideoTimeline.audio(prepared.path, 0))
                assertEquals(1, audio.trackCount)
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, audio.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally { audio.release() }
            val input = TurnRequestContract.input("", listOf(prepared.path))
            assertEquals("localImage", input.getJSONObject(2).getString("type"))
            assertEquals("localAudio", input.getJSONObject(3).getString("type"))
            assertTrue(LocalMediaAttachmentPolicy.ownedPaths(prepared.path).all { File(it).length() > 0 })
        } finally { directory.deleteRecursively() }
    }
}
