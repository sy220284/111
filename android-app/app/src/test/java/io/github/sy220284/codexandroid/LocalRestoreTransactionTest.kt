package io.github.sy220284.codexandroid

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalRestoreTransactionTest {
    @Test fun interruptionRollsBackExistingAndNewFilesAcrossDirectories() {
        val root = Files.createTempDirectory("restore-transaction").toFile()
        try {
            val live = File(root, "live").apply { mkdir() }
            val existing = File(live, "settings").apply { writeText("original") }
            val added = File(live, "new/project.txt")
            val incoming = File(root, "incoming").apply { mkdir() }
            val first = File(incoming, "settings").apply { writeText("replacement") }
            val second = File(incoming, "project").apply { writeText("new project") }
            val targets = listOf(first to existing, second to added)
            val journal = File(root, "rollback")
            LocalRestoreTransaction(journal).apply {
                prepare(targets)
                assertEquals("original", existing.readText())
                apply(targets)
            }
            assertEquals("replacement", existing.readText())
            assertTrue(added.isFile)
            LocalRestoreTransaction(journal).apply {
                assertTrue(hasJournal())
                rollback(targets)
                rollback(targets) // interrupted rollback is idempotent
                finish()
            }
            assertEquals("original", existing.readText())
            assertFalse(added.exists())
            assertFalse(added.parentFile!!.exists())
            assertFalse(journal.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun writeFailureLeavesOriginalSnapshotAvailable() {
        val root = Files.createTempDirectory("restore-failure").toFile()
        try {
            val a = File(root, "a").apply { writeText("old") }
            val b = File(root, "b")
            val source = File(root, "source").apply { writeText("new") }
            val targets = listOf(source to a, source to b)
            val transaction = LocalRestoreTransaction(File(root, "journal"))
            transaction.prepare(targets)
            assertThrows(IOException::class.java) {
                transaction.apply(targets) { if (it == 1) throw IOException("injected write failure") }
            }
            transaction.rollback(targets)
            assertEquals("old", a.readText())
            assertFalse(b.exists())
            transaction.finish()
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulCommitKeepsReplacementAndRemovesJournal() {
        val root = Files.createTempDirectory("restore-commit").toFile()
        try {
            val live = File(root, "live").apply { writeText("old") }
            val source = File(root, "incoming").apply { writeText("new") }
            val transaction = LocalRestoreTransaction(File(root, "journal"))
            val targets = listOf(source to live)
            transaction.prepare(targets)
            transaction.apply(targets)
            transaction.finish()
            assertEquals("new", live.readText())
            assertFalse(transaction.hasJournal())
        } finally { root.deleteRecursively() }
    }
}
