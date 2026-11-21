package io.github.sy220284.codexandroid

import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs in an actual Android emulator: starts the packaged executable and
 * negotiates a real JSON-RPC session, without requiring the user's login.
 */
@RunWith(AndroidJUnit4::class)
class CoreStartupInstrumentedTest {
    @Test fun bundledCoreCanPrintVersionOnAndroid() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val binary = File(app.applicationInfo.nativeLibraryDir, "libcodex_server.so")
        org.junit.Assert.assertTrue("Missing native core: " + binary.path, binary.isFile)
        val process = ProcessBuilder(binary.absolutePath, "--version")
            .redirectErrorStream(true).start()
        try {
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            org.junit.Assert.assertTrue("Codex binary --version hung on Android", finished)
            val output = process.inputStream.bufferedReader().readText()
            org.junit.Assert.assertEquals("Codex --version exit code; output=" + output, 0, process.exitValue())
            org.junit.Assert.assertTrue("No version text: " + output, output.isNotBlank())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun localThreadCanStartAndBeReadWithoutCloudLogin() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                val workspace = File(app.filesDir, "projects").apply { mkdirs() }
                val created = bridge.request("thread/start",
                    JSONObject().put("cwd", workspace.absolutePath))
                val id = created.getJSONObject("thread").getString("id")
                org.junit.Assert.assertTrue("Thread id empty", id.isNotBlank())
                val read = bridge.request("thread/read",
                    JSONObject().put("threadId", id).put("includeTurns", false))
                assertEquals(id, read.getJSONObject("thread").getString("id"))
            }
        } catch (failure: Exception) {
            throw AssertionError("Offline thread lifecycle failed; status=" +
                bridge.status.value + "; stderr=" + bridge.recentStderr.value.joinToString(" / "), failure)
        } finally {
            bridge.close()
        }
    }

    @Test fun bundledCoreInitializesAndAnswersAccountRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                assertEquals(EngineStatus.Connected, bridge.status.value)
                val response = bridge.request("account/read")
                assertNotNull(response)
                val dir = File(app.filesDir, "projects").apply { mkdirs() }
                val shellCommand = JSONObject()
                    .put("command", JSONArray()
                        .put("/system/bin/sh").put("-c")
                        .put("printf codex-android-exec-ok"))
                    .put("cwd", dir.absolutePath)
                    .put("sandboxPolicy", JSONObject()
                        .put("type", "externalSandbox")
                        .put("networkAccess", "restricted"))
                    .put("timeoutMs", 15_000)
                val commandResult = bridge.request("command/exec", shellCommand)
                assertEquals("Android shell execution failed: " +
                    commandResult.optString("stderr"),
                    0, commandResult.optInt("exitCode", -1))
                assertEquals("codex-android-exec-ok",
                    commandResult.optString("stdout").trim())
            }
        } catch (failure: Exception) {
            throw AssertionError(
                "Android Codex core start failed. ABI=" + Build.SUPPORTED_ABIS.joinToString() +
                "; status=" + bridge.status.value +
                "; recentStderr=" + bridge.recentStderr.value.joinToString(" / "),
                failure
            )
        } finally {
            bridge.close()
        }
    }
    @Test fun terminalStreamsUtf8AndAcceptsStdinOnRealAndroidCore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                val root = File(app.filesDir, "projects").apply { mkdirs() }
                val processId = UUID.randomUUID().toString()
                var output = TerminalSessionState.start(processId, "read user input")
                val ready = CompletableDeferred<Unit>()
                val replied = CompletableDeferred<Unit>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    bridge.events.collect { event ->
                        if (event.optString("method") == "command/exec/outputDelta") {
                            val params = event.optJSONObject("params") ?: return@collect
                            if (params.optString("processId") != processId) return@collect
                            output = output.append(params)
                            if (output.stdoutText.contains("ready")) ready.complete(Unit)
                            if (output.stdoutText.contains("ack:测试")) replied.complete(Unit)
                        }
                    }
                }
                try {
                    val job = async(Dispatchers.IO) {
                        bridge.request("command/exec",
                            TerminalSessionContract.execute(
                                "printf ready; read value; printf 'ack:%s' \"\$value\"",
                                root.absolutePath, processId
                            ), timeoutMillis = null)
                    }
                    withTimeout(20_000L) { ready.await() }
                    bridge.request("command/exec/write",
                        TerminalSessionContract.write(processId, "测试"))
                    val result = withTimeout(20_000L) { job.await() }
                    withTimeout(10_000L) { replied.await() }
                    assertEquals(0, result.getInt("exitCode"))
                    org.junit.Assert.assertTrue(output.stdoutText.contains("ack:测试"))
                } finally {
                    collector.cancelAndJoin()
                }
            }
        } finally {
            bridge.close()
        }
    }

    @Test fun terminalLongRunningProcessCanBeStoppedByProcessId() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                val root = File(app.filesDir, "projects").apply { mkdirs() }
                val id = UUID.randomUUID().toString()
                val started = CompletableDeferred<Unit>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    bridge.events.collect { event ->
                        if (event.optString("method") == "command/exec/outputDelta" &&
                            event.optJSONObject("params")?.optString("processId") == id) {
                            started.complete(Unit)
                        }
                    }
                }
                try {
                    val job = async(Dispatchers.IO) {
                        bridge.request("command/exec",
                            TerminalSessionContract.execute(
                                "printf running; sleep 60",
                                root.absolutePath, id
                            ), timeoutMillis = null)
                    }
                    withTimeout(20_000L) { started.await() }
                    bridge.request("command/exec/terminate",
                        TerminalSessionContract.terminate(id))
                    val result = withTimeout(20_000L) { job.await() }
                    org.junit.Assert.assertTrue("终止后的进程应返回非零码",
                        result.getInt("exitCode") != 0)
                } finally {
                    collector.cancelAndJoin()
                }
            }
        } finally {
            bridge.close()
        }
    }

}
