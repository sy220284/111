package io.github.sy220284.codexandroid

import android.os.Build
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
import kotlinx.coroutines.flow.first
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
    @Test fun nativeCoreDiscoversAndCallsAndroidPreviewMcp() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        val host = ModelPreviewMcpServer { args, meta ->
            val (path, line) = ModelPreviewPolicy.target(args)
            check(path == "demo.txt" && line == 2)
            JSONObject().put("status", "queued").put("threadId", ModelPreviewPolicy.thread(meta))
        }
        try {
            host.launch()
            withTimeout(90000) {
                bridge.connect()
                val workspace = WorkspaceStorage.root(app)
                val params = JSONObject().put("cwd", workspace.absolutePath)
                    .put("config", JSONObject().put("mcp_servers." + ModelPreviewMcpServer.NAME, host.config()))
                val id = bridge.request("thread/start", params).getJSONObject("thread").getString("id")
                val statusParams = CatalogRequestContract.mcpStatus(id).put("serverName", ModelPreviewMcpServer.NAME)
                var ready = false
                repeat(12) {
                    if (!ready) {
                        val status = bridge.request("mcpServerStatus/list", statusParams)
                        ready = records(status).any { row -> row.optString("name") == ModelPreviewMcpServer.NAME &&
                            row.optJSONObject("tools")?.has(ModelPreviewMcpServer.TOOL) == true }
                        if (!ready) kotlinx.coroutines.delay(500)
                    }
                }
                org.junit.Assert.assertTrue("native MCP tool discovery did not find Android preview", ready)
                val result = bridge.request("mcpServer/tool/call", JSONObject().put("threadId", id)
                    .put("server", ModelPreviewMcpServer.NAME).put("tool", ModelPreviewMcpServer.TOOL)
                    .put("arguments", JSONObject("""{"target":{"type":"file","path":"demo.txt","line":2}}"""))
                    .put("_meta", JSONObject().put("threadId", id)))
                org.junit.Assert.assertFalse(result.optBoolean("isError"))
                assertEquals("queued", result.getJSONObject("structuredContent").getString("status"))
                assertEquals(id, result.getJSONObject("structuredContent").getString("threadId"))
            }
        } finally {
            bridge.close()
            host.stop()
        }
    }

    @Test fun nativeDeltaPatcherLoadsAndRejectsCorruptPatch() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val library = File(app.applicationInfo.nativeLibraryDir, "libhpatchz.so")
        org.junit.Assert.assertTrue("HDiffPatch JNI missing: ${library.path}", library.isFile)
        val root = File(app.cacheDir, "update-patcher-test").apply { mkdirs() }
        val original = File(root, "original")
        val invalid = File(root, "invalid.hpatch")
        val output = File(root, "output")
        try {
            original.writeText("old-111-release")
            invalid.writeText("invalid patch payload")
            val result = com.github.sisong.HPatch.patch(
                original.absolutePath, invalid.absolutePath, output.absolutePath,
                1024 * 1024L, 1, true
            )
            org.junit.Assert.assertTrue("Corrupt delta was accepted", result != 0)
        } finally {
            original.delete()
            invalid.delete()
            output.delete()
        }
    }

    @Test fun nativeDeltaPatcherRestoresRealWindowZstdFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val assets = instrumentation.context.assets
        val root = File(app.cacheDir, "update-real-delta-test").apply { mkdirs() }
        val source = File(root, "old.apk")
        val patch = File(root, "delta.hpatch")
        val target = File(root, "new.apk")
        try {
            assets.open("update-old.bin").use { source.writeBytes(it.readBytes()) }
            assets.open("update-delta.hpatch").use { patch.writeBytes(it.readBytes()) }
            val expected = assets.open("update-new.bin").use { it.readBytes() }
            val code = com.github.sisong.HPatch.patch(
                source.absolutePath, patch.absolutePath, target.absolutePath,
                8L * 1024 * 1024, 2, true
            )
            assertEquals("HDiffPatch -WD zstd xxh128 restore returned $code", 0, code)
            org.junit.Assert.assertArrayEquals("Reconstructed content must match the release target",
                expected, target.readBytes())
        } finally {
            source.delete()
            patch.delete()
            target.delete()
        }
    }

    @Test fun bundledNativeGitCanTrackLocalProject() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bin = NativeToolInstaller.prepare(app)
        val workspace = WorkspaceStorage.root(app)
        val folder = File(workspace, "git-android-verification").apply { mkdirs() }
        val file = File(folder, "CODEX-VERIFIED.txt")
        file.writeText("codex git android test")
        val shell = "git init -q && git add CODEX-VERIFIED.txt && " +
            "git -c user.name=Codex -c user.email=codex@example.invalid " +
            "commit -qm verified && git status --porcelain && git log -1 --format=%s"
        val process = ProcessBuilder("/system/bin/sh", "-c", shell)
            .directory(folder)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${bin.absolutePath}:/system/bin" }
            .start()
        try {
            val ended = process.waitFor(30, TimeUnit.SECONDS)
            org.junit.Assert.assertTrue("Native Git did not finish", ended)
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("Native Git failed: $output", 0, process.exitValue())
            org.junit.Assert.assertTrue("No persisted commit: $output",
                output.lines().any { it.trim() == "verified" })
        } finally {
            process.destroyForcibly()
            WorkspaceFileActions.delete(workspace, folder)
        }
    }

    @Test fun bundledRipgrepSearchesAndroidProjectWorkspace() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bin = NativeToolInstaller.prepare(app)
        val workspace = WorkspaceStorage.root(app)
        val fixture = File(workspace, "rg-native-integration.txt")
        fixture.writeText("codex-rg-native-confirmation\n")
        val process = ProcessBuilder("/system/bin/sh", "-c",
            "rg --no-config --line-number codex-rg-native-confirmation rg-native-integration.txt")
            .directory(workspace)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = "${bin.absolutePath}:/system/bin" }
            .start()
        try {
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            org.junit.Assert.assertTrue("ripgrep execution timed out", finished)
            val result = process.inputStream.bufferedReader().readText()
            assertEquals("ripgrep failed on Android: $result", 0, process.exitValue())
            org.junit.Assert.assertTrue("ripgrep did not find the test file: $result",
                result.contains("1:codex-rg-native-confirmation"))
        } finally {
            process.destroyForcibly()
            fixture.delete()
        }
    }

    @Test fun bundledCodeModeHostCanInitializeV8OnAndroid() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val host = File(app.applicationInfo.nativeLibraryDir, "libcodex_code_mode_host.so")
        org.junit.Assert.assertTrue("Missing Code Mode Host: ${host.path}", host.isFile)
        val process = ProcessBuilder(host.absolutePath, "--android-v8-self-test")
            .redirectErrorStream(true).start()
        try {
            val finished = process.waitFor(25, TimeUnit.SECONDS)
            org.junit.Assert.assertTrue("Host V8 initialization timed out", finished)
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("Host failed to initialize Android V8: $output", 0, process.exitValue())
            org.junit.Assert.assertTrue("No positive V8 initialization marker: $output",
                output.contains("codex-android-v8-initialized"))
            org.junit.Assert.assertTrue("Sandbox V8 was not enabled: $output",
                output.contains("codex-android-v8-sandbox-enabled"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun bundledCodeModeHostStartsAndServesHealthcheck() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val host = File(app.applicationInfo.nativeLibraryDir, "libcodex_code_mode_host.so")
        org.junit.Assert.assertTrue("Missing Code Mode Host: ${host.path}", host.isFile)
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(host.absolutePath, "--listen", "grpc://127.0.0.1:$port")
            .start()
        try {
            var healthy = false
            var observation = "Host did not answer"
            for (attempt in 0 until 40) {
                if (!process.isAlive) {
                    observation = "Host exited with code ${process.exitValue()}"
                    break
                }
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                        socket.soTimeout = 1000
                        socket.getOutputStream().write(
                            "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII)
                        )
                        val response = socket.getInputStream().bufferedReader().readLine()
                        observation = response ?: "Empty HTTP health response"
                        healthy = response?.contains(" 200 ") == true
                    }
                    if (healthy) break
                } catch (error: IOException) {
                    observation = error.message ?: error.javaClass.simpleName
                }
                Thread.sleep(200)
            }
            org.junit.Assert.assertTrue("Code Mode Host failed to start on Android: $observation", healthy)
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

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

    @Test fun localProjectRegistryAndThreadBindingWorkOnRealCore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        val workspace = WorkspaceStorage.root(app)
        val name = "Android 验证 " + UUID.randomUUID()
        try {
            withTimeout(90_000L) {
                bridge.connect()
                val created = bridge.request("project/create",
                    ProjectRequestContract.create(workspace, workspace, name))
                val project = created.getJSONObject("project")
                val projectId = project.getString("id")
                assertEquals(name, project.getString("name"))
                val read = bridge.request("project/read",
                    ProjectRequestContract.read(projectId))
                assertEquals(projectId, read.getJSONObject("project").getString("id"))
                val started = bridge.request("thread/start",
                    ProjectRequestContract.threadStart(workspace, projectId, ""))
                assertEquals(projectId, started.getJSONObject("thread").getString("projectId"))
                val updated = bridge.request("project/update",
                    ProjectRequestContract.rename(projectId, "Android 修改验证"))
                assertEquals("Android 修改验证",
                    updated.getJSONObject("project").getString("name"))
                val listed = bridge.request("project/list",
                    JSONObject().put("limit", 100))
                org.junit.Assert.assertTrue("Created project missing from registry",
                    records(listed).any { it.optString("id") == projectId })
                bridge.request("project/delete", ProjectRequestContract.read(projectId))
            }
        } finally {
            bridge.close()
        }
    }

    @Test fun androidPtySupportsInteractiveInputAndResize() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                val root = WorkspaceStorage.root(app)
                val id = UUID.randomUUID().toString()
                val ready = CompletableDeferred<Unit>()
                val resultText = CompletableDeferred<Unit>()
                var state = TerminalSessionState.start(id, "PTY integration", tty = true)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    bridge.events.collect { message ->
                        if (message.optString("method") != "command/exec/outputDelta") return@collect
                        val params = message.optJSONObject("params") ?: return@collect
                        if (params.optString("processId") != id) return@collect
                        state = state.append(params)
                        if (state.stdoutText.contains("pty-ready")) ready.complete(Unit)
                        if (state.stdoutText.contains("pty-done")) resultText.complete(Unit)
                    }
                }
                try {
                    val job = async(Dispatchers.IO) {
                        bridge.request("command/exec",
                            TerminalSessionContract.execute(
                                "if [ -t 0 ]; then printf pty-ready; read value; " +
                                    "printf pty-done; else exit 7; fi",
                                root.absolutePath, id, tty = true
                            ), timeoutMillis = null)
                    }
                    withTimeout(20_000L) { ready.await() }
                    bridge.request("command/exec/resize",
                        TerminalSessionContract.resize(id, 36, 105))
                    bridge.request("command/exec/write",
                        TerminalSessionContract.write(id, "confirmed"))
                    val result = withTimeout(20_000L) { job.await() }
                    withTimeout(10_000L) { resultText.await() }
                    assertEquals("PTY command failed: " + state.stdoutText,
                        0, result.getInt("exitCode"))
                } finally {
                    collector.cancelAndJoin()
                }
            }
        } finally {
            bridge.close()
        }
    }

    @Test fun localThreadCanStartAndBeReadWithoutCloudLogin() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                val workspace = WorkspaceStorage.root(app)
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

    @Test fun realCoreCrashRecoversWithoutReplayingAndRejectsRetiredSession() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        var child: Process? = null
        val bridge = CodexConnection(app) { builder -> builder.start().also { child = it } }
        try {
            withTimeout(120_000L) {
                bridge.connect()
                val old = checkNotNull(bridge.connectedSession.value)
                val first = checkNotNull(child)
                assertNotNull(bridge.request("account/read"))
                first.destroyForcibly()
                bridge.connectedSession.first { it == null }
                org.junit.Assert.assertFalse(first.isAlive)
                bridge.connect()
                val current = checkNotNull(bridge.connectedSession.value)
                org.junit.Assert.assertTrue(current > old)
                org.junit.Assert.assertNotSame(first, child)
                try {
                    bridge.request("account/read", expectedSession = old)
                    org.junit.Assert.fail("Retired request was redirected to the replacement core")
                } catch (failure: IllegalStateException) {
                    org.junit.Assert.assertTrue(failure.message.orEmpty().contains("切换"))
                }
                assertNotNull(bridge.request("account/read", expectedSession = current))
            }
        } finally {
            bridge.close()
        }
    }

    @Test fun reservedRestartWaitsForConfirmedProcessExitBeforeCredentialChange() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val allowExitReceipt = java.util.concurrent.CountDownLatch(1)
        val boundedWait = CompletableDeferred<Unit>()
        var realChild: Process? = null
        // Keep a real native child and its real pipes. Delay only the observed exit
        // receipt to exercise the slow-retirement path deterministically.
        val bridge = CodexConnection(app) { builder ->
            val actual = builder.start().also { realChild = it }
            object : Process() {
                override fun getOutputStream() = actual.outputStream
                override fun getInputStream() = actual.inputStream
                override fun getErrorStream() = actual.errorStream
                override fun exitValue() = actual.exitValue()
                override fun isAlive() = actual.isAlive
                override fun destroy() = actual.destroy()
                override fun destroyForcibly(): Process {
                    actual.destroyForcibly()
                    return this
                }
                override fun waitFor(): Int {
                    val exit = actual.waitFor()
                    allowExitReceipt.await()
                    return exit
                }
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    boundedWait.complete(Unit)
                    return false
                }
            }
        }
        try {
            withTimeout(90_000L) {
                bridge.connect()
                org.junit.Assert.assertTrue(bridge.reserveRestart())
                try {
                    val close = async(Dispatchers.IO) { bridge.closeForRestart { true } }
                    boundedWait.await()
                    org.junit.Assert.assertNull("凭据变更不能先于旧内核退出确认",
                        kotlinx.coroutines.withTimeoutOrNull(500L) { close.await(); true })
                    org.junit.Assert.assertTrue(bridge.retirementBusy.value)
                    allowExitReceipt.countDown()
                    close.await()
                    org.junit.Assert.assertFalse(checkNotNull(realChild).isAlive)
                    org.junit.Assert.assertNull(bridge.connectedSession.value)
                    org.junit.Assert.assertFalse(bridge.retirementBusy.value)
                } finally {
                    allowExitReceipt.countDown()
                    bridge.releaseRestart()
                }
            }
        } finally {
            allowExitReceipt.countDown()
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
                val dir = WorkspaceStorage.root(app)
                val fixture = File(dir, "shared-storage-${UUID.randomUUID()}.txt")
                fixture.writeText("phone-file")
                try {
                val shellCommand = JSONObject()
                    .put("command", JSONArray()
                        .put("/system/bin/sh").put("-c")
                        .put("test \"$(cat '${fixture.name}')\" = phone-file && printf codex-write > '${fixture.name}' && printf codex-android-exec-ok"))
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
                assertEquals("codex-write", fixture.readText())
                } finally {
                    fixture.delete()
                }
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
                val root = WorkspaceStorage.root(app)
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
                val root = WorkspaceStorage.root(app)
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
