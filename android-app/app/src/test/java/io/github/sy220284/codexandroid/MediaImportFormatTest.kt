package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class MediaImportFormatTest {
    @Test fun normalizesProviderMimeAndKnownAliases() {
        assertEquals("mp4", MediaImportFormat.extension("Video/MP4; charset=binary", null))
        assertEquals("mp4", MediaImportFormat.extension("video/mpeg4", null))
        assertEquals("m4a", MediaImportFormat.extension("audio/mp4", null))
        assertEquals("ogg", MediaImportFormat.extension("application/ogg", null))
    }

    @Test fun genericProviderUsesOnlyAllowedDisplayNameSuffix() {
        assertEquals("mp4", MediaImportFormat.extension("application/octet-stream", "clip.MP4"))
        assertEquals("mp4", MediaImportFormat.extension("application/mp4", "clip.mp4"))
        assertEquals("m4a", MediaImportFormat.extension("application/mp4", "clip.m4a"))
        assertEquals("jpg", MediaImportFormat.extension(null, "photo.JPEG"))
        assertEquals("wav", MediaImportFormat.extension("audio/*", "voice.wav"))
    }

    @Test fun rejectsUnsupportedAndCrossCategoryMetadata() {
        listOf("application/pdf" to "clip.mp4", "audio/*" to "clip.mp4",
            "video/*" to "clip.wav", "application/mp4" to "photo.jpg",
            "application/octet-stream" to "clip.exe", null to null).forEach { (mime, name) ->
            assertThrows(IllegalArgumentException::class.java) { MediaImportFormat.extension(mime, name) }
        }
    }
}
