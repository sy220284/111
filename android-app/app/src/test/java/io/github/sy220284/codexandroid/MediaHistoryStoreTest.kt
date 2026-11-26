package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MediaHistoryStoreTest {
    @Test fun nativeMessageIdentityRestoresOriginalVideoAndAudioAcrossInstances() {
        val root = Files.createTempDirectory("media-history").toFile()
        try {
            val video = File(root, UUID.randomUUID().toString() + ".mp4").apply { writeText("video") }
            val audio = File(root, UUID.randomUUID().toString() + ".wav").apply { writeText("audio") }
            val id = UUID.randomUUID().toString()
            val paths = listOf(video.canonicalPath, audio.canonicalPath)
            MediaHistoryStore.record(root, id, paths)
            MediaHistoryStore.record(root, id, paths)
            assertEquals(paths, MediaHistoryStore.read(root, id))
            assertTrue(MediaHistoryStore.read(root, UUID.randomUUID().toString()).isEmpty())
            assertTrue(MediaHistoryStore.read(root, "../outside").isEmpty())
            assertThrows(IllegalArgumentException::class.java) { MediaHistoryStore.record(root, id, listOf(audio.path)) }
            video.delete()
            assertEquals(paths, MediaHistoryStore.read(root, id))
        } finally { root.deleteRecursively() }
    }
}
