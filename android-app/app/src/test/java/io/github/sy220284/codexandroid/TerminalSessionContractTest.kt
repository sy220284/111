package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class TerminalSessionContractTest {
    private fun delta(id: String, stream: String, text: String) = JSONObject()
        .put("processId", id)
        .put("stream", stream)
        .put("deltaBase64", Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8)))
        .put("capReached", false)

    @Test fun executionUsesRealProcessIdAndStreamingContract() {
        val request = TerminalSessionContract.execute("pwd && ls", "/sandbox/work", "process-123")
        assertEquals("process-123", request.getString("processId"))
        assertTrue(request.getBoolean("streamStdoutStderr"))
        assertTrue(request.getBoolean("streamStdin"))
        assertEquals("/sandbox/work", request.getString("cwd"))
        assertEquals("/system/bin/sh", request.getJSONArray("command").getString(0))
        assertEquals("-c", request.getJSONArray("command").getString(1))
        assertEquals("pwd && ls", request.getJSONArray("command").getString(2))
        assertTrue(request.getBoolean("disableTimeout"))
    }

    @Test fun appendPreservesUnicodeSplitAcrossStreamChunks() {
        val original = "中文日志 ✓"
        val chars = original.toByteArray(StandardCharsets.UTF_8)
        val s0 = TerminalSessionState.start("a", "echo")
        val first = JSONObject().put("processId", "a").put("stream", "stdout")
            .put("deltaBase64", Base64.getEncoder().encodeToString(chars.copyOfRange(0, 2)))
            .put("capReached", false)
        val second = JSONObject().put("processId", "a").put("stream", "stdout")
            .put("deltaBase64", Base64.getEncoder().encodeToString(chars.copyOfRange(2, chars.size)))
            .put("capReached", false)
        val finished = s0.append(first).append(second)
        assertEquals(original, finished.stdoutText)
    }

    @Test fun otherProcessesCannotPolluteOutput() {
        val s = TerminalSessionState.start("current", "echo")
        val result = s.append(delta("unrelated", "stdout", "foreign"))
        assertSame(s, result)
        assertEquals("", result.stdoutText)
    }

    @Test fun stderrAndStdoutAreKeptSeparately() {
        val s = TerminalSessionState.start("a", "echo")
            .append(delta("a", "stdout", "normal"))
            .append(delta("a", "stderr", "warning"))
        assertEquals("normal", s.stdoutText)
        assertEquals("warning", s.stderrText)
    }

    @Test fun boundedOutputReportsTruncation() {
        val huge = "x".repeat(TerminalSessionState.MAX_STREAM_BYTES + 1)
        val s = TerminalSessionState.start("a", "echo")
            .append(delta("a", "stdout", huge))
        assertEquals(TerminalSessionState.MAX_STREAM_BYTES, s.stdoutBytes.size)
        assertTrue(s.outputTruncated)
        assertEquals("x".repeat(TerminalSessionState.MAX_STREAM_BYTES), s.stdoutText)
    }

    @Test fun laterOutputRemainsVisibleAfterBufferLimit() {
        val first = "A".repeat(TerminalSessionState.MAX_STREAM_BYTES)
        val state = TerminalSessionState.start("a", "tail")
            .append(delta("a", "stdout", first))
            .append(delta("a", "stdout", "LATEST"))
        assertEquals(TerminalSessionState.MAX_STREAM_BYTES, state.stdoutBytes.size)
        assertTrue(state.stdoutText.endsWith("LATEST"))
        assertTrue(state.outputTruncated)
    }

    @Test fun stdinAndStopUseExactSessionId() {
        val stdin = TerminalSessionContract.write("session-a", "第一行")
        assertEquals("session-a", stdin.getString("processId"))
        assertEquals("第一行\n", String(Base64.getDecoder().decode(stdin.getString("deltaBase64")), StandardCharsets.UTF_8))
        assertFalse(stdin.getBoolean("closeStdin"))
        assertEquals("session-a", TerminalSessionContract.terminate("session-a").getString("processId"))
    }

    @Test fun finishRetainsOutputAndExitCode() {
        val s = TerminalSessionState.start("a", "echo").append(delta("a", "stdout", "hello"))
            .finish(JSONObject().put("exitCode", 7).put("stdout", "").put("stderr", "details"))
        assertFalse(s.running)
        assertTrue(s.completed)
        assertEquals(7, s.exitCode)
        assertEquals("hello", s.stdoutText)
        assertEquals("details", s.stderrText)
    }

    @Test fun malformedOutputReturnsVisibleErrorInsteadOfDataCorruption() {
        val s = TerminalSessionState.start("a", "cmd")
        try {
            s.append(JSONObject().put("processId", "a").put("stream", "stdout")
                .put("deltaBase64", "%%%").put("capReached", false))
            fail("非法 Base64 应明确报错")
        } catch (_: IllegalArgumentException) {
            assertEquals("", s.stdoutText)
        }
    }
}
