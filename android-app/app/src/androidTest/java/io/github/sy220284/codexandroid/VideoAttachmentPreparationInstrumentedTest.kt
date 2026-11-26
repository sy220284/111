package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.runBlocking
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
        val context = instrumentation.targetContext
        // Use the existing private composer FileProvider as a real content URI source.
        val directory = File(context.cacheDir, "codex-camera/media-test-" + java.util.UUID.randomUUID())
        check(directory.mkdirs())
        var managedPath: String? = null
        try {
            val bytes = instrumentation.context.assets.open("media/blue-aac.mp4.b64").use {
                Base64.decode(it.readBytes(), Base64.DEFAULT)
            }
            assertEquals("4e4ed97b4bf73b0d23818873698ce32d29fee6d66a16a1ca169a8b5e01741ec1", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            val source = File(directory, java.util.UUID.randomUUID().toString() + ".mp4").apply { writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.composerprovider", source)
            // Keep the provider metadata in failure diagnostics; the real import must succeed.
            val mime = context.contentResolver.getType(uri)
            val imported = File(runBlocking {
                try { LocalImageAttachments.importFrom(context, uri) }
                catch (failure: Exception) { throw AssertionError("MP4 content URI import failed; provider MIME=$mime", failure) }
            }).also { managedPath = it.path }
            assertEquals(LocalImageAttachments.directory(context).canonicalFile, imported.canonicalFile.parentFile)
            assertNotEquals(source.canonicalPath, imported.canonicalPath)
            assertArrayEquals(bytes, imported.readBytes())
            val pendingDraft = ComposerDraft("未发送的视频", listOf(imported.path))
            val deferred = DraftPersistence.resolveStoredImages(LocalImageAttachments.directory(context),
                DraftPersistence.decode(DraftPersistence.encode(mapOf("video" to pendingDraft))))
            assertEquals(0, deferred.missingImages)
            assertEquals(pendingDraft, deferred.drafts.getValue("video"))
            assertFalse(File(VideoTimeline.manifest(imported.path)).exists())
            assertTrue(LocalMediaAttachmentPolicy.hasAudio(imported.path))
            val visual = VideoAttachmentPreparation.prepareTracks(imported, includeImage = true, includeAudio = false)
            assertTrue(visual.hasVisual)
            assertTrue(visual.segments.all { it.audioStartUs == null })
            assertFalse(File(VideoTimeline.audio(imported.path, 0)).exists())
            val sheet = File(VideoTimeline.sheet(imported.path, 0))
            val visualBytes = sheet.readBytes()
            val visualModified = sheet.lastModified()
            val audioOnly = VideoAttachmentPreparation.prepareTracks(imported, includeImage = false, includeAudio = true)
            assertFalse(audioOnly.hasVisual)
            assertTrue(audioOnly.segments.all { it.times.isEmpty() })
            assertTrue(audioOnly.segments.any { it.audioStartUs != null })
            assertEquals(visualModified, sheet.lastModified())
            assertArrayEquals(visualBytes, sheet.readBytes())
            assertFalse(File(VideoTimeline.manifest(imported.path)).exists())
            val prepared = VideoAttachmentPreparation.prepare(imported)
            assertEquals(visualModified, sheet.lastModified())
            assertEquals(imported.path, prepared.path)
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
            val wavs = AudioPcmPreparation.clips(File(VideoTimeline.audio(prepared.path, 0)), directory) {}
            assertEquals(1, wavs.size)
            val wav = wavs.single().readBytes()
            assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
            assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
            assertTrue(wav.size > 44 && wav.size < LocalImageAttachments.MAX_AUDIO_BYTES)
            // 16 kHz mono PCM16 retains the two-second clip after AAC priming removal.
            assertTrue((wav.size - 44) / 32_000.0 in 1.8..2.2)
            val roundTrip = DraftPersistence.encode(mapOf("video" to ComposerDraft(images = listOf(prepared.path))))
            assertEquals(listOf(prepared.name), DraftPersistence.decode(roundTrip).getValue("video").images)
            val restored = DraftPersistence.resolveStoredImages(LocalImageAttachments.directory(context), DraftPersistence.decode(roundTrip))
            assertEquals(0, restored.missingImages)
            val restoredPaths = restored.drafts.getValue("video").images
            assertEquals(listOf(prepared.canonicalPath), restoredPaths)
            assertArrayEquals(bytes, File(restoredPaths.single()).readBytes())
            val input = TurnRequestContract.input("", restoredPaths)
            assertEquals("localImage", input.getJSONObject(2).getString("type"))
            assertEquals("localAudio", input.getJSONObject(3).getString("type"))
            assertTrue(LocalMediaAttachmentPolicy.ownedPaths(prepared.path).all { File(it).length() > 0 })
        } finally {
            try { managedPath?.let { path -> runBlocking { LocalImageAttachments.release(context, path) } } }
            finally { directory.deleteRecursively() }
        }
    }
}
