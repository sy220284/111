package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Deterministic fake native process: never executes code on the test host. */
private class MockCodexProcess(lines: List<String>) : Process() {
    private val stdin = ByteArrayOutputStream()
    private val stdout = ByteArrayInputStream(
        (if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n"))
            .toByteArray(Charsets.UTF_8))
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() {}

    fun writes(): List<JSONObject> = stdin.toString(Charsets.UTF_8.name())
        .trimEnd('\r', '\n').lines().filter(String::isNotEmpty).map(::JSONObject)
}

class RpcProcessChannelTest {
    @Test fun revokedAudioNeverWritesAnyBytesToNativeProcess() {
        val process = MockCodexProcess(emptyList())
        val channel = RpcProcessChannel(process)
        val voice = VoiceUploadGate()
        val revision = voice.revision
        val frame = JSONObject().put("id", 1).put("method", "thread/realtime/appendAudio")
        voice.setMuted(true)
        try {
            channel.send(frame) { voice.accepts(revision) }
            fail("静音后待写入的音频必须被拒绝")
        } catch (_: RpcWriteRevoked) {
            assertTrue(process.writes().isEmpty())
        }
        voice.setMuted(false)
        channel.send(frame) { voice.accepts(voice.revision) }
        assertEquals(1, process.writes().size)
        channel.closeWriter()
    }

    @Test fun readsAllKindsOfNativeFramesInOrderAndWritesOneJsonPerLine() = runBlocking {
        val lines = listOf(
            """{"id":12,"result":{"thread":{"id":"real-01"}}}""",
            """{"id":"approve-1","method":"item/commandExecution/requestApproval","params":{"command":"pwd"}}""",
            """{"method":"item/agentMessage/delta","params":{"delta":"测试"}}""",
            """{"method":"serverRequest/resolved","params":{"requestId":"approve-1"}}""",
            """{"id":"unknown-1","method":"item/future/request","params":{}}"""
        )
        val process = MockCodexProcess(lines)
        val channel = RpcProcessChannel(process)
        val types = mutableListOf<RpcEnvelopeKind>()
        withTimeout(5_000) {
            channel.readFrames { message -> types += RpcEnvelopeRouter.classify(message) }
        }
        assertEquals(listOf(
            RpcEnvelopeKind.Response, RpcEnvelopeKind.HostRequest,
            RpcEnvelopeKind.Event, RpcEnvelopeKind.RequestResolved,
            RpcEnvelopeKind.UnsupportedHostRequest
        ), types)

        channel.send(JSONObject().put("method", "initialize").put("id", 8)
            .put("params", JSONObject().put("clientInfo", JSONObject())))
        channel.send(JSONObject().put("method", "initialized").put("params", JSONObject()))
        channel.send(JSONObject().put("id", "approve-1")
            .put("result", JSONObject().put("decision", "decline")))
        val wire = process.writes()
        assertEquals(3, wire.size)
        assertEquals("initialize", wire[0].getString("method"))
        assertEquals("initialized", wire[1].getString("method"))
        assertEquals("decline", wire[2].getJSONObject("result").getString("decision"))
        channel.closeWriter()
    }

    @Test fun malformedResultFromProcessIsRejectedAtRpcBoundary() = runBlocking {
        val process = MockCodexProcess(listOf(
            """{"id":1,"result":{"thread":{"id":"thread-1"}}}""",
            """{"id":2,"result":null}"""
        ))
        val channel = RpcProcessChannel(process)
        val results = mutableListOf<JSONObject>()
        try {
            channel.readFrames { envelope ->
                if (RpcEnvelopeRouter.classify(envelope) == RpcEnvelopeKind.Response) {
                    results += RpcResponseContract.requireResult("thread/start", envelope)
                }
            }
            fail("应拒绝内核缺失的响应结果")
        } catch (failure: IllegalStateException) {
            assertEquals(1, results.size)
            assertEquals("thread-1", results[0].getJSONObject("thread").getString("id"))
            assertTrue(failure.message.orEmpty().contains("结果为空或类型错误"))
        } finally {
            channel.closeWriter()
        }
    }

    @Test fun diagnosticLinesDoNotDisconnectOrExposeTheirContents() = runBlocking {
        val channel = RpcProcessChannel(MockCodexProcess(listOf(
            "tool diagnostic", "[INFO] another diagnostic", "  ", """{"id":9,"result":{}}""",
            """{"method":"turn/completed","params":{}}"""
        )))
        val frames = mutableListOf<RpcEnvelopeKind>()
        var diagnosed = 0
        channel.readFrames(onNonProtocolLine = { diagnosed++ }) {
            frames += RpcEnvelopeRouter.classify(it)
        }
        assertEquals(2, diagnosed)
        assertEquals(listOf(RpcEnvelopeKind.Response, RpcEnvelopeKind.Event), frames)
        channel.closeWriter()
    }

    @Test fun closeWaitsForTheWholeAdmittedFrame() {
        val process = MockCodexProcess(emptyList())
        val channel = RpcProcessChannel(process)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val write = workers.submit {
                channel.send(JSONObject().put("id", 7).put("result", JSONObject())) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = workers.submit {
                closing.countDown()
                channel.closeWriter()
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            write.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)
            assertEquals(7, process.writes().single().getInt("id"))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun rejectsMalformedFrameInsteadOfSilentlySkippingIt() = runBlocking {
        val channel = RpcProcessChannel(MockCodexProcess(listOf(
            """{"method":"turn/started","params":{}}""", "{invalid json"
        )))
        val methods = mutableListOf<String>()
        try {
            channel.readFrames { methods += it.getString("method") }
            fail("Invalid native output must terminate the protocol reader")
        } catch (_: JSONException) {
            assertEquals(listOf("turn/started"), methods)
        } finally {
            channel.closeWriter()
        }
    }

    @Test fun emptyProcessOutputProducesNoSpuriousEvents() = runBlocking {
        val channel = RpcProcessChannel(MockCodexProcess(emptyList()))
        var count = 0
        withTimeout(5_000) { channel.readFrames { count++ } }
        assertEquals(0, count)
        channel.closeWriter()
    }
}
