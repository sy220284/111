package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ServerPrompt(val id: Any, val method: String, val params: JSONObject)
sealed interface EngineStatus {
    data object Starting : EngineStatus
    data object Connected : EngineStatus
    data class Unavailable(val reason: String) : EngineStatus
}

/** Official Codex app-server uses newline-delimited JSON-RPC without the jsonrpc field. */
class CodexConnection(private val appContext: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val sequence = AtomicLong(1)
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    /** Live wire requests, including direct calls from settings, session recovery and terminal. */
    internal val activeRpcRequests = MutableStateFlow<Set<Long>>(emptySet())
    @Volatile private var process: Process? = null
    @Volatile private var wire: RpcProcessChannel? = null
    private val shutdownHook = Thread({ close() }, "codex-native-process-cleanup")

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    val status = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
    val events = RpcEventBuffer.create()
    val prompts = MutableStateFlow<List<ServerPrompt>>(emptyList())
    val recentStderr = MutableStateFlow<List<String>>(emptyList())

    suspend fun connect() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
        if (status.value == EngineStatus.Connected) return@withContext
        status.value = EngineStatus.Starting
        // Retire a failed/stale process before opening a new JSON-RPC stream.
        val stale = process
        process = null
        wire = null
        stale?.destroy()
        failWaiting("运行核心正在重新连接")
        prompts.value = emptyList()
        recentStderr.value = emptyList()
        val path = File(appContext.applicationInfo.nativeLibraryDir, "libcodex_server.so")
        val codeModeHost = File(appContext.applicationInfo.nativeLibraryDir, "libcodex_code_mode_host.so")
        val home = File(appContext.filesDir, "codex-home").apply { mkdirs() }
        val workspace = File(appContext.filesDir, "projects").apply { mkdirs() }
        val temp = File(appContext.cacheDir, "codex-tmp").apply { mkdirs() }
        val builder = ProcessBuilder(path.absolutePath, "--listen", "stdio://")
        builder.directory(workspace)
        builder.environment().apply {
            put("HOME", appContext.filesDir.absolutePath)
            put("CODEX_HOME", home.absolutePath)
            put("TMPDIR", temp.absolutePath)
            put("PATH", "/system/bin:/system/xbin")
            // Native CLI helpers may link against the NDK C++ runtime packaged
            // next to the executable in the APK's extracted native library dir.
            put("LD_LIBRARY_PATH", appContext.applicationInfo.nativeLibraryDir)
            put("TERM", "xterm-256color")
        }
        try {
            McpAgentSkillInstaller.install(appContext, home)
            require(path.isFile) { "APK 中未找到当前设备 ABI 的 Codex 运行核心，请检查发布包。" }
            require(codeModeHost.isFile) {
                "APK 缺少当前设备 ABI 的 Code Mode Host，请安装包含完整运行组件的版本。"
            }
            val toolsDir = NativeToolInstaller.prepare(appContext)
            builder.environment()["PATH"] = "${toolsDir.absolutePath}:/system/bin:/system/xbin"
            val child = builder.start()
            process = child
            val channel = RpcProcessChannel(child)
            wire = channel
            scope.launch { listen(child, channel) }
            scope.launch {
                val exitCode = child.waitFor()
                if (process === child) {
                    val reason = "Codex 运行核心已退出，退出码 $exitCode"
                    status.value = EngineStatus.Unavailable(reason)
                    failWaiting(reason)
                    prompts.value = emptyList()
                }
            }
            scope.launch {
                try {
                    child.errorStream.bufferedReader().use { stream ->
                        // Drain stderr to avoid a blocked child; keep diagnostics only in local memory.
                        while (true) {
                            val line = stream.readLine() ?: break
                            if (process !== child) break
                            recentStderr.value = (recentStderr.value + StderrSanitizer.redact(line.take(350)))
                                .takeLast(16)
                        }
                    }
                } catch (failure: IOException) {
                    // Destroying a child closes its pipes from another thread; readLine then
                    // raises InterruptedIOException on Android. It is a completed shutdown.
                    // A failure while this child is still owned is a real connection fault.
                    if (process === child) {
                        val reason = "Codex 错误输出通道读取失败：" +
                            (failure.message ?: failure.javaClass.simpleName)
                        process = null
                        wire = null
                        status.value = EngineStatus.Unavailable(reason)
                        prompts.value = emptyList()
                        failWaiting(reason)
                        child.destroy()
                    }
                }
            }
            val info = JSONObject().put("name", "codex_android")
                .put("title", "Codex Android").put("version", "0.1.0")
            val capabilities = JSONObject().put("experimentalApi", true).put("requiredHumanApproval", true)
            request("initialize", JSONObject().put("clientInfo", info)
                .put("capabilities", capabilities))
            notify("initialized", JSONObject())
            check(process === child && child.isAlive) { "Codex 核心在初始化后已退出" }
            status.value = EngineStatus.Connected
        } catch (failure: Exception) {
            val failedProcess = process
            val exit = failedProcess?.takeIf { !it.isAlive }?.runCatching { exitValue() }?.getOrNull()
            process = null
            wire = null
            failedProcess?.destroy()
            val reason = (failure.message ?: "Codex 内核启动失败") +
                (if (exit == null) "" else " (退出码 " + exit + ")")
            status.value = EngineStatus.Unavailable(reason)
            failWaiting(reason)
            throw failure
        }
        }
    }

    private suspend fun listen(child: Process, channel: RpcProcessChannel) =
        withContext(Dispatchers.IO) {
        try {
            channel.readFrames { message ->
                    val id = message.opt("id")
                    val method = message.optString("method")
                    when (RpcEnvelopeRouter.classify(message)) {
                        RpcEnvelopeKind.Response -> {
                            waiting.remove((id as Number).toLong())?.complete(message)
                        }
                        RpcEnvelopeKind.MalformedResponse -> {
                            throw IllegalStateException("内核响应缺少有效的数字请求编号")
                        }
                        RpcEnvelopeKind.HostRequest -> {
                            prompts.value = prompts.value + ServerPrompt(
                                id, method,
                                message.optJSONObject("params") ?: JSONObject()
                            )
                        }
                        RpcEnvelopeKind.UnsupportedHostRequest -> {
                            write(JSONObject().put("id", id).put("error",
                                JSONObject().put("code", -32601)
                                    .put("message", RpcEnvelopeRouter.unsupportedReason(method))))
                            events.emit(JSONObject().put("method", "local/unsupportedServerRequest")
                                .put("params", JSONObject().put("method", method)
                                    .put("reason", RpcEnvelopeRouter.unsupportedReason(method))))
                        }
                        RpcEnvelopeKind.RequestResolved -> {
                            val resolved = message.optJSONObject("params")?.opt("requestId")
                            prompts.value = prompts.value.filterNot {
                                it.id.toString() == resolved.toString()
                            }
                            events.emit(message)
                        }
                        RpcEnvelopeKind.Event -> events.emit(message)
                    }
            }
            if (process === child) {
                val code = child.takeIf { !it.isAlive }?.runCatching { exitValue() }?.getOrNull()
                throw java.io.EOFException(
                    "Codex 标准输出通道已关闭" + (if (code == null) "" else "，退出码 $code")
                )
            }
        } catch (error: Exception) {
            if (process === child) {
                val reason = "协议连接中断：" + (error.message ?: "未知错误")
                status.value = EngineStatus.Unavailable(reason)
                failWaiting(reason)
            }
        }
    }

    private fun failWaiting(reason: String) {
        waiting.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        waiting.clear()
    }

    suspend fun request(
        method: String,
        params: JSONObject? = JSONObject(),
        timeoutMillis: Long? = when (method) {
            "thread/read", "thread/resume" -> 120_000L
            else -> 45_000L
        }
    ): JSONObject {
        ProtocolDirectionPolicy.requireClientSend(method)
        val id = sequence.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        waiting[id] = deferred
        activeRpcRequests.update { it + id }
        try {
            write(RpcWireRequest.envelope(method, id, params))
            // Long-running terminal sessions have a user-visible Stop action.
            // All ordinary RPCs retain the bounded 45-second timeout.
            val response = if (timeoutMillis == null) deferred.await()
                else withTimeout(timeoutMillis) { deferred.await() }
            return RpcResponseContract.requireResult(method, response)
        } finally {
            waiting.remove(id)
            activeRpcRequests.update { it - id }
        }
    }

    suspend fun notify(method: String, params: JSONObject = JSONObject()) {
        ProtocolDirectionPolicy.requireClientSend(method)
        write(JSONObject().put("method", method).put("params", params))
    }

    suspend fun answer(prompt: ServerPrompt, result: JSONObject) {
        write(JSONObject().put("id", prompt.id).put("result", result))
        prompts.value = prompts.value.filterNot { it.id.toString() == prompt.id.toString() }
    }

    private suspend fun write(message: JSONObject) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val channel = wire ?: throw IllegalStateException("Codex 内核尚未连接")
                channel.send(message)
            }
        }
    }

    fun close() {
        val current = process
        val output = wire
        process = null
        wire = null
        // Close the protocol pipe, request a graceful exit, then reap the child.
        try {
            output?.closeWriter()
        } catch (failure: IOException) {
            android.util.Log.w("CodexConnection", "关闭内核输入流失败", failure)
        }
        current?.destroy()
        if (current != null && !current.waitFor(400, TimeUnit.MILLISECONDS)) {
            current.destroyForcibly()
        }
        status.value = EngineStatus.Unavailable("连接已关闭")
        prompts.value = emptyList()
        recentStderr.value = emptyList()
        failWaiting("连接已关闭")
    }
}
