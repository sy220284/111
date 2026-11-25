package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AttachmentRetentionPolicyTest {
    @Test fun expiredButReferencedImageAlwaysSurvives() {
        val folder = Files.createTempDirectory("codex-image-retention").toFile()
        try {
            val now = System.currentTimeMillis()
            val referenced = File(folder, "draft.png").apply {
                writeText("draft")
                setLastModified(now - 9L * AttachmentRetentionPolicy.ORPHAN_TTL_MILLIS)
            }
            val orphan = File(folder, "orphan.png").apply {
                writeText("orphan")
                setLastModified(now - 2L * AttachmentRetentionPolicy.ORPHAN_TTL_MILLIS)
            }
            val recent = File(folder, "recent.png").apply { writeText("recent") }
            val historical = File(folder, "sent.png").apply {
                writeText("sent")
                setLastModified(now - 9L * AttachmentRetentionPolicy.ORPHAN_TTL_MILLIS)
            }
            repeat(2) {
                AttachmentRetentionPolicy.preserveForHistory(folder, listOf(historical.canonicalPath))
            }
            assertEquals(1, AttachmentRetentionPolicy.prune(
                folder, setOf(referenced.canonicalPath), now))
            assertTrue(referenced.isFile)
            assertFalse(orphan.exists())
            assertTrue(recent.isFile)
            assertTrue(historical.isFile)
            assertTrue(AttachmentRetentionPolicy.isHistorical(folder, historical))
        } finally {
            folder.deleteRecursively()
        }
    }
    @Test fun editingAnotherDraftOrRemovingWhileTaskRunsNeverDeletesLiveImage() {
        val saved = listOf(ComposerDraft("新指令", listOf("image-A")))
        assertFalse(AttachmentRetentionPolicy.canRelease("image-A", saved, emptyList()))
        assertFalse(AttachmentRetentionPolicy.canRelease(
            "image-B", emptyList(), listOf("image-B")))
        assertTrue(AttachmentRetentionPolicy.canRelease("image-C", saved, listOf("image-B")))
        assertTrue(AttachmentRetentionPolicy.canRelease("image-A", emptyList(), emptyList()))
    }

    @Test fun missingDirectoryIsNoOp() {
        val root = Files.createTempDirectory("codex-root").toFile()
        try { assertEquals(0, AttachmentRetentionPolicy.prune(
            File(root, "absent"), emptySet())) }
        finally { root.deleteRecursively() }
    }

    @Test fun videoAndSidecarsAreRetainedTogether() {
        val folder = Files.createTempDirectory("codex-video-retention").toFile()
        try {
            val path = File(folder, "source.av.mp4").canonicalPath
            val now = System.currentTimeMillis()
            val owned = LocalMediaAttachmentPolicy.ownedPaths(path).map { File(it).apply {
                writeText("test")
                setLastModified(now - 2 * AttachmentRetentionPolicy.ORPHAN_TTL_MILLIS)
            } }
            assertEquals(0, AttachmentRetentionPolicy.prune(folder, setOf(path), now))
            assertTrue(owned.all { it.isFile })
            AttachmentRetentionPolicy.preserveForHistory(folder, listOf(path))
            assertEquals(0, AttachmentRetentionPolicy.prune(folder, emptySet(), now))
            assertTrue(owned.all { AttachmentRetentionPolicy.isHistorical(folder, it) })
        } finally { folder.deleteRecursively() }
    }
}
