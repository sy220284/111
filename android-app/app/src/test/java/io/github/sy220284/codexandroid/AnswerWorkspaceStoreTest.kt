package io.github.sy220284.codexandroid

import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnswerWorkspaceStoreTest {
    @Test fun retryUsesPreTurnFilesAndDoesNotOverwriteOtherVersions() = runBlocking {
        val base = Files.createTempDirectory("answer-workspace").toFile()
        try {
            val root = base.resolve("root").apply { mkdir() }
            val source = root.resolve("project").apply { mkdir() }
            val original = source.resolve("main.txt").apply { writeText("before") }
            val store = AnswerWorkspaceStore(root, base.resolve("checkpoints"))
            val client = UUID.randomUUID().toString()
            store.checkpoint(client, source.path)
            original.writeText("original answer")
            val second = store.prepare(client, source.path, false).directory
            assertEquals("before", second.resolve("main.txt").readText())
            second.resolve("main.txt").writeText("second answer")
            val third = store.prepare(client, source.path, false).directory
            assertEquals("before", third.resolve("main.txt").readText())
            assertEquals("original answer", original.readText())
            assertEquals("second answer", second.resolve("main.txt").readText())
            assertEquals(":workspace", AnswerWorkspaceStore.permission(root, second.path, ":danger-full-access"))
        } finally { base.deleteRecursively() }
    }
    @Test fun missingBaselineRequiresExplicitCurrentFileChoice() = runBlocking {
        val base = Files.createTempDirectory("answer-missing").toFile()
        try {
            val root = base.resolve("root").apply { mkdir() }
            root.resolve("file.txt").writeText("current")
            val store = AnswerWorkspaceStore(root, base.resolve("checkpoints"))
            try {
                store.prepare(null, root.path, false)
                fail("不得静默使用当前文件")
            } catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("快照")) }
            assertEquals("current", store.prepare(null, root.path, true).directory.resolve("file.txt").readText())
        } finally { base.deleteRecursively() }
    }
}
