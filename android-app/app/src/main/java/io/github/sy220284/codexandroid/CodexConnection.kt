package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
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

data class ServerPrompt(val id: Any, val method: String, val params: JSONObject, internal val connectionSession: Long? = null)

internal class RpcWriteRevoked : CancellationException("待发送数据的许可已撤销")
internal const val CORE_SESSION_FIELD = "_androidCoreSession"
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
    private val ownership = CoreSessionOwnership()
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
    private val connectedSessionState = MutableStateFlow<Long?>(null)
    internal val connectedSession = connectedSessionState.asStateFlow()
    private val eventBuffer = RpcEventBuffer.create()
    val events = eventBuffer.filter(::isCurrentEvent)
    internal fun isCurrentEvent(message: JSONObject): Boolean =
        ownership.owns(message.optLong(CORE_SESSION_FIELD, -1L))

    internal fun reserveRestart(): Boolean = synchronized(ownership) {
        ownership.reserveRestart(activeRpcRequests.value.size, prompts.value.size)
    }
    internal fun releaseRestart() = ownership.releaseRestart()
    val prompts = MutableStateFlow<List<ServerPrompt>>(emptyList())
    val recentStderr = MutableStateFlow<List<String>>(emptyList())

    suspend fun connect() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
        val epoch = synchronized(ownership) {
            if (status.value == EngineStatus.Connected) return@withContext
            val token = ownership.begin()
            status.value = EngineStatus.Starting
            prompts.value = emptyList()
            recentStderr.value = emptyList()
            token
        }
        try {
            val path = File(appContext.applicationInfo.nativeLibraryDir, "libcodex_server.so")
            val codeModeHost = File(appContext.applicationInfo.nativeLibraryDir, "libcodex_code_mode_host.so")
            val home = File(appContext.filesDir, "codex-home").apply { mkdirs() }
            val temp = File(appContext.cacheDir, "codex-tmp").apply { mkdirs() }
            val builder = ProcessBuilder(path.absolutePath, "--listen", "stdio://")
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
            builder.directory(WorkspaceStorage.root(appContext))
            McpAgentSkillInstaller.install(appContext, home)
            require(path.isFile) { "APK 中未找到当前设备 ABI 的 Codex 运行核心，请检查发布包。" }
            require(codeModeHost.isFile) {
                "APK 缺少当前设备 ABI 的 Code Mode Host，请安装包含完整运行组件的版本。"
            }
            val toolsDir = NativeToolInstaller.prepare(appContext)
            builder.environment()["PATH"] = "${toolsDir.absolutePath}:/system/bin:/system/xbin"
            val child = builder.start()
            val channel = RpcProcessChannel(child)
            val attached = synchronized(ownership) {
                if (!ownership.owns(epoch)) false else {
                    process = child
                    wire = channel
                    true
                }
            }
            if (!attached) {
                terminate(child, channel)
                error("内核启动已取消")
            }
            scope.launch { listen(epoch, child, channel) }
            scope.launch {
                val exitCode = child.waitFor()
                retireSession(epoch, "Codex 运行核心已退出，退出码 $exitCode")
            }
            scope.launch {
                try {
                    child.errorStream.bufferedReader().use { stream ->
                        // Drain stderr to avoid a blocked child; keep diagnostics only in local memory.
                        while (true) {
                            val line = stream.readLine() ?: break
                            val accepted = synchronized(ownership) {
                                if (!ownership.owns(epoch)) false else {
                                    recentStderr.value = (recentStderr.value + StderrSanitizer.redact(line.take(350))).takeLast(16)
                                    true
                                }
                            }
                            if (!accepted) break
                        }
                    }
                } catch (failure: IOException) {
                    // Destroying a child closes its pipes from another thread; readLine then
                    // raises InterruptedIOException on Android. It is a completed shutdown.
                    // A failure while this child is still owned is a real connection fault.
                    retireSession(epoch, "Codex 错误输出通道读取失败：" +
                        (failure.message ?: failure.javaClass.simpleName))
                }
            }
            val info = JSONObject().put("name", "codex_android")
                .put("title", "Codex Android").put("version", "0.1.0")
            val capabilities = JSONObject().put("experimentalApi", true).put("requiredHumanApproval", true)
            requestForSession("initialize", JSONObject().put("clientInfo", info)
                .put("capabilities", capabilities), 45_000, epoch, null)
            write(epoch, JSONObject().put("method", "initialized").put("params", JSONObject()))
            synchronized(ownership) {
                check(child.isAlive && ownership.markReady(epoch)) { "Codex 核心在初始化后已退出" }
                connectedSessionState.value = epoch
                status.value = EngineStatus.Connected
            }
        } catch (failure: Exception) {
            retireSession(epoch, failure.message ?: "Codex 内核启动失败")
            throw failure
        }
        }
    }

    private suspend fun listen(epoch: Long, child: Process, channel: RpcProcessChannel) =
        withContext(Dispatchers.IO) {
            try {
                channel.readFrames { message ->
                    if (!ownership.owns(epoch)) return@readFrames
                    val id = message.opt("id")
                    val method = message.optString("method")
                    when (RpcEnvelopeRouter.classify(message)) {
                        RpcEnvelopeKind.Response -> synchronized(ownership) {
                            if (ownership.owns(epoch)) waiting.remove((id as Number).toLong())?.complete(message)
                        }
                        RpcEnvelopeKind.MalformedResponse -> {
                            throw IllegalStateException("内核响应缺少有效的数字请求编号")
                        }
                        RpcEnvelopeKind.HostRequest -> synchronized(ownership) {
                            if (ownership.owns(epoch)) prompts.value = prompts.value + ServerPrompt(
                                id, method, message.optJSONObject("params") ?: JSONObject(), epoch)
                        }
                        RpcEnvelopeKind.UnsupportedHostRequest -> {
                            write(epoch, JSONObject().put("id", id).put("error",
                                JSONObject().put("code", -32601)
                                    .put("message", RpcEnvelopeRouter.unsupportedReason(method))))
                            publish(epoch, JSONObject().put("method", "local/unsupportedServerRequest")
                                .put("params", JSONObject().put("method", method)
                                    .put("reason", RpcEnvelopeRouter.unsupportedReason(method))))
                        }
                        RpcEnvelopeKind.RequestResolved -> {
                            synchronized(ownership) {
                                if (ownership.owns(epoch)) {
                                    val resolved = message.optJSONObject("params")?.opt("requestId")
                                    prompts.value = prompts.value.filterNot { it.id.toString() == resolved.toString() }
                                }
                            }
                            publish(epoch, message)
                        }
                        RpcEnvelopeKind.Event -> publish(epoch, message)
                    }
                }
                if (ownership.owns(epoch)) {
                    val code = child.takeIf { !it.isAlive }?.runCatching { exitValue() }?.getOrNull()
                    retireSession(epoch, "Codex 标准输出通道已关闭" + (if (code == null) "" else "，退出码 $code"))
                }
            } catch (error: Exception) {
                retireSession(epoch, "协议连接中断：" + (error.message ?: "未知错误"))
                if (error is CancellationException) throw error
            }
        }

    private suspend fun publish(epoch: Long, message: JSONObject) {
        if (ownership.owns(epoch)) eventBuffer.emit(message.put(CORE_SESSION_FIELD, epoch))
    }

    // Caller holds the ownership monitor. No new request can register in the retired session.
    private fun failWaiting(reason: String) {
        waiting.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        waiting.clear()
    }

    suspend fun request(
        method: String,
        params: JSONObject? = JSONObject(),
        timeoutMillis: Long? = when (method) {
            "thread/read", "thread/resume", "app/list", "app/installed", "plugin/list", "plugin/installed" -> 120_000L
            else -> 45_000L
        },
        sendPermit: (() -> Boolean)? = null,
        expectedSession: Long? = null
    ): JSONObject = requestForSession(method, params, timeoutMillis, null, sendPermit, expectedSession)

    private suspend fun requestForSession(
        method: String, params: JSONObject?, timeoutMillis: Long?, startupEpoch: Long?,
        sendPermit: (() -> Boolean)?, expectedSession: Long? = null
    ): JSONObject {
        ProtocolDirectionPolicy.requireClientSend(method)
        val id = sequence.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        val epoch = synchronized(ownership) {
            val token = ownership.current() ?: error("Codex 内核尚未连接")
            check(expectedSession == null || expectedSession == token) { "操作所属内核已切换，请刷新状态" }
            check(if (startupEpoch == null) ownership.acceptsUserRequest() else startupEpoch == token) {
                "运行核心正在连接或重启，请完成后重试"
            }
            waiting[id] = deferred
            activeRpcRequests.update { it + id }
            token
        }
        try {
            write(epoch, RpcWireRequest.envelope(method, id, params), sendPermit)
            // Long-running terminal sessions have a user-visible Stop action.
            val response = if (timeoutMillis == null) deferred.await()
                else withTimeout(timeoutMillis) { deferred.await() }
            check(ownership.owns(epoch)) { "运行核心已切换，请刷新状态" }
            return RpcResponseContract.requireResult(method, response)
        } finally {
            synchronized(ownership) {
                waiting.remove(id)
                activeRpcRequests.update { it - id }
            }
        }
    }

    suspend fun notify(method: String, params: JSONObject = JSONObject()) {
        ProtocolDirectionPolicy.requireClientSend(method)
        val epoch = synchronized(ownership) {
            check(ownership.acceptsUserRequest()) { "运行核心正在连接或重启" }
            ownership.current() ?: error("Codex 内核尚未连接")
        }
        write(epoch, JSONObject().put("method", method).put("params", params), requireReady = true)
    }

    suspend fun answer(prompt: ServerPrompt, result: JSONObject) {
        val epoch = synchronized(ownership) {
            check(ownership.acceptsUserRequest() && prompts.value.contains(prompt)) { "审批已过期，请刷新当前状态" }
            val token = ownership.current() ?: error("Codex 内核尚未连接")
            check(prompt.connectionSession == token) { "审批所属内核已切换" }
            token
        }
        write(epoch, JSONObject().put("id", prompt.id).put("result", result), requireReady = true)
        synchronized(ownership) {
            if (ownership.owns(epoch)) prompts.value = prompts.value.filterNot { it == prompt }
        }
    }

    private suspend fun write(epoch: Long, message: JSONObject, permit: (() -> Boolean)? = null,
        requireReady: Boolean = false) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val channel = synchronized(ownership) {
                    check(ownership.owns(epoch) && (!requireReady || ownership.acceptsUserRequest())) {
                        "运行核心已切换或正在重启"
                    }
                    wire ?: error("Codex 内核尚未连接")
                }
                // Last admission point after dispatcher and writer queue. The captured
                // channel cannot be redirected to a newly connected core.
                channel.send(message) {
                    check(ownership.owns(epoch) && (!requireReady || ownership.acceptsUserRequest())) {
                        "运行核心已切换或正在重启"
                    }
                    permit?.invoke() != false
                }
            }
        }
    }

    private fun terminate(child: Process?, channel: RpcProcessChannel?) {
        // Kill/reap before publishing disconnection. Destroying first also unblocks
        // a writer stuck in a full pipe, rather than waiting on BufferedWriter.close().
        child?.destroy()
        if (child != null && !child.waitFor(400, TimeUnit.MILLISECONDS)) {
            child.destroyForcibly()
            child.waitFor()
        }
        try {
            channel?.closeWriter()
        } catch (failure: IOException) {
            android.util.Log.w("CodexConnection", "关闭内核输入流失败", failure)
        }
    }

    private fun retireSession(epoch: Long, reason: String, reservedRestart: Boolean = false) {
        val retired = synchronized(ownership) {
            if (reservedRestart) check(ownership.canRetireReserved(activeRpcRequests.value.size, prompts.value.size)) {
                "重启前出现了新请求或审批，已取消重启"
            }
            val token = ownership.retire(epoch) ?: return
            val child = process
            val channel = wire
            process = null
            wire = null
            failWaiting(reason)
            prompts.value = emptyList()
            recentStderr.value = emptyList()
            Triple(token, child, channel)
        }
        terminate(retired.second, retired.third)
        synchronized(ownership) {
            if (ownership.completeRetirement(retired.first)) {
                connectedSessionState.value = null
                status.value = EngineStatus.Unavailable(reason)
            }
        }
    }

    internal fun closeForRestart() {
        val epoch = synchronized(ownership) {
            check(ownership.canRetireReserved(activeRpcRequests.value.size, prompts.value.size)) {
                "重启前出现了新请求或审批，已取消重启"
            }
            ownership.current()
        } ?: return
        retireSession(epoch, "连接已关闭", reservedRestart = true)
    }

    fun close() {
        val epoch = ownership.current() ?: return
        retireSession(epoch, "连接已关闭")
    }
}
