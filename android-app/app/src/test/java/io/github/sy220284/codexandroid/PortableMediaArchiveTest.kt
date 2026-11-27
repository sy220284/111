package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PortableMediaArchiveTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    @Test fun portableNamesCoverOriginalsSidecarsHistoryAndAnalysisOnly() {
        for (name in listOf("$id.jpg", "$id.mp3", "$id.av.mp4", "$id.silent.mp4", "$id.mp4.metadata.json",
            "$id.mp4.timeline.json", "$id.mp4.visual.timeline.json", "$id.mp4.audio.timeline.json", "$id.mp4.segment-9.m4a")) {
            assertTrue(name, BackupDataPolicy.allows("files/codex-turn-images/$name"))
            assertTrue(name, BackupDataPolicy.allows("files/codex-turn-images/history/$name"))
        }
        assertTrue(BackupDataPolicy.mayContain("files/codex-turn-images/history"))
        assertTrue(BackupDataPolicy.allows("files/codex-turn-images/history/message-$id.json"))
        assertTrue(BackupDataPolicy.allows("files/media-analysis-results/" + "a".repeat(64) + "-" + "b".repeat(64) + ".json"))
        for (name in listOf("$id.mp4.segment-10.jpg", "$id.mp4.exec", "auth.json", "message-$id.tmp"))
            assertFalse(name, BackupDataPolicy.allows("files/codex-turn-images/$name"))
        assertFalse(BackupDataPolicy.allows("files/media-analysis-results/config.json"))
    }
    @Test fun restoreRebasesSourcesAndRecordIdentityWhileKeepingPaidResultsAndHistory() {
        val root = Files.createTempDirectory("portable-media").toFile()
        try {
            val files = File(root, "incoming/files").apply { check(mkdirs()) }
            val media = File(files, PortableMediaFormat.ATTACHMENTS).apply { check(mkdirs()) }
            val source = File(media, "$id.mp3").apply { writeText("audio bytes") }
            MediaHistoryStore.record(media, id, listOf(source.path))
            AttachmentRetentionPolicy.preserveForHistory(media, listOf(source.path))
            val record = AnalysisCheckpoint("thread", "a".repeat(64), source.path, "Understand", "question", listOf("paid result"), 1)
            AnalysisCheckpointStore(File(files, PortableMediaFormat.ANALYSES)).save(record, false)
            val targetFiles = File(root, "destination/files")
            PortableMediaArchive.relocate(files, targetFiles)
            val restored = AnalysisCheckpointStore(File(files, PortableMediaFormat.ANALYSES)).listMedia(record.owner, record.scope).single()
            assertEquals(File(targetFiles, "codex-turn-images/${source.name}").path, restored.source)
            assertNotEquals(record.identity, restored.identity)
            assertEquals(record.parts, restored.parts)
            assertEquals(listOf(source.path), MediaHistoryStore.read(media, id))
            assertTrue(AttachmentRetentionPolicy.isHistorical(media, source))
            assertTrue(source.delete())
            assertThrows(IllegalArgumentException::class.java) { PortableMediaArchive.verify(files) }
        } finally { check(root.deleteRecursively()) }
    }
    @Test fun incompleteVideoAndSpoofedAnalysisOwnershipAreRejectedBeforeRestore() {
        val root = Files.createTempDirectory("portable-media-invalid").toFile()
        try {
            val media = File(root, PortableMediaFormat.ATTACHMENTS).apply { check(mkdirs()) }
            val source = File(media, "$id.mp4").apply { writeText("video bytes") }
            File(VideoTimeline.manifest(source.path)).writeText(VideoTimeline(1000,
                listOf(VideoTimeline.Segment(0, 1000, listOf(0, 500), null))).encode())
            assertThrows(IllegalArgumentException::class.java) { PortableMediaArchive.verify(root) }
            File(VideoTimeline.sheet(source.path, 0)).writeText("frame bytes")
            PortableMediaArchive.verify(root)
            val record = AnalysisCheckpoint("thread", "a".repeat(64), source.path, "Understand", "question")
            val folder = File(root, PortableMediaFormat.ANALYSES).apply { check(mkdirs()) }
            File(folder, "b".repeat(64) + ".json").writeText(record.encode())
            assertThrows(IllegalArgumentException::class.java) { PortableMediaArchive.verify(root) }
        } finally { check(root.deleteRecursively()) }
    }
}
