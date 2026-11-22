package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class TerminalTranscriptStoreTest {
    private fun sandbox() = File(System.getProperty("java.io.tmpdir"),
        "codex-transcript-test-" + java.util.UUID.randomUUID())

    @Test fun splitUnicodeAndBothStreamsCanBeExportedWithoutLosingOutput() = runBlocking {
        val dir = sandbox()
        try {
            val logs = TerminalTranscriptStore(dir)
            logs.begin("session-A")
            assertTrue(logs.available.value)
            val unicode = "中文日志 ✓".toByteArray(StandardCharsets.UTF_8)
            logs.append("session-A", "stdout", unicode.copyOfRange(0, 2))
            logs.append("session-A", "stdout", unicode.copyOfRange(2, unicode.size))
            logs.append("session-A", "stderr", "warning".toByteArray())
            val dest = ByteArrayOutputStream()
            logs.export("session-A", "echo run", dest)
            val content = dest.toString("UTF-8")
            assertTrue(content.contains("中文日志 ✓"))
            assertTrue(content.contains("warning"))
            assertTrue(content.contains("echo run"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun quotaReportsVisibleWarningAndNoMixedCommandTranscripts() = runBlocking {
        val dir = sandbox()
        try {
            val logs = TerminalTranscriptStore(dir, maxStoredBytes = 5)
            logs.begin("first")
            logs.append("first", "stdout", "123456789".toByteArray())
            assertTrue(logs.error.value!!.contains("上限"))
            assertTrue(logs.available.value)
            val first = ByteArrayOutputStream()
            logs.export("first", "cmd", first)
            assertTrue(first.toString("UTF-8").contains("12345"))
            assertFalse(first.toString("UTF-8").contains("123456789"))
            logs.begin("second")
            logs.append("first", "stdout", "OLD".toByteArray())
            logs.append("second", "stdout", "NEW".toByteArray())
            val second = ByteArrayOutputStream()
            logs.export("second", "next", second)
            assertTrue(second.toString("UTF-8").contains("NEW"))
            assertFalse(second.toString("UTF-8").contains("OLD"))
            assertEquals(null, logs.error.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun historicalStdoutPagesRemainContiguousAcrossUtf8Boundaries() = runBlocking {
        val dir = sandbox()
        try {
            val logs = TerminalTranscriptStore(dir)
            logs.begin("history")
            val original = ("你好，Terminal ✓\n").repeat(400)
            logs.append("history", "stdout", original.toByteArray(Charsets.UTF_8))
            var before: Long? = null
            val pages = mutableListOf<String>()
            var hasMore: Boolean
            do {
                val page = logs.readStdoutBefore(
                    "history", before, visibleTailBytes = 0, pageBytes = 311)
                pages.add(0, page.text)
                before = page.beforeOffset
                hasMore = page.hasMore
            } while (hasMore)
            assertEquals(original, pages.joinToString(""))
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalStateException::class)
    fun historicalReadCannotCrossCommandBoundary(): Unit = runBlocking {
        val dir = sandbox()
        try {
            val logs = TerminalTranscriptStore(dir)
            logs.begin("first")
            logs.append("first", "stdout", ByteArray(140000) { 65 })
            logs.begin("second")
            logs.readStdoutBefore("first")
        } finally { dir.deleteRecursively() }
    }

    @Test(expected = IllegalStateException::class)
    fun cannotExportOldSessionAfterNextCommand() = runBlocking {
        val dir = sandbox()
        try {
            val logs = TerminalTranscriptStore(dir)
            logs.begin("first")
            logs.begin("second")
            logs.export("first", "old", ByteArrayOutputStream())
        } finally {
            dir.deleteRecursively()
        }
    }
}
