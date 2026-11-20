package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
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
    // Process IO is managed by separate coroutines and may also be closed on the UI thread.
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null

    val status = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
    val events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 512)
    val prompts = MutableStateFlow<List<ServerPrompt>>(emptyList())
    val recentStderr = MutableStateFlow<List<String>>(emptyList())

    suspend fun connect() = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
        if (status.value == EngineStatus.Connected) return@withContext
        status.value = EngineStatus.Starting
        recentStderr.value = emptyList()
        val path = File(appContext.applicationInfo.nativeLibraryDir, "libcodex_server.so")
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
            put("TERM", "xterm-256color")
        }
        try {
            require(path.isFile) { "APK 中未找到当前设备 ABI 的 Codex 运行核心，请检查发布包。" }
            val child = builder.start()
            process = child
            writer = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))
            scope.launch { listen(child) }
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
                        // Drain stderr and retain only a bounded device-local diagnostic tail.
                        while (true) {
                            val line = stream.readLine() ?: break
                            if (process !== child) break
                            recentStderr.value = (recentStderr.value + line.take(350))
                                .takeLast(16)
                        }
                    }
                } catch (error: IOException) {
                    if (process === child) {
                        // Unexpected stderr failure is a transport fault. On an intentional
                        // close(), process is cleared first; Android may then interrupt a
                        // pending read with InterruptedIOException, which is expected.
                        val reason = "Codex 错误输出通道中断：" + (error.message ?: error.javaClass.simpleName)
                        status.value = EngineStatus.Unavailable(reason)
                        failWaiting(reason)
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
            writer = null
            failedProcess?.destroy()
            val reason = (failure.message ?: "Codex 内核启动失败") +
                (if (exit == null) "" else " (退出码 " + exit + ")")
            status.value = EngineStatus.Unavailable(reason)
            failWaiting(reason)
            throw failure
        }
        }
    }

    private suspend fun listen(child: Process) = withContext(Dispatchers.IO) {
        try {
            child.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val message = JSONObject(line)
                    val id = message.opt("id")
                    val method = message.optString("method")
                    if (id is Number && method.isEmpty()) {
                        waiting.remove(id.toLong())?.complete(message)
                    } else if (method.isNotEmpty() && id != null && id != JSONObject.NULL) {
                        if (method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                                "item/permissions/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request")) {
                            prompts.value = prompts.value + ServerPrompt(id, method, message.optJSONObject("params") ?: JSONObject())
                        } else {
                            // Unsupported host requests must be answered, or the whole turn stalls forever.
                            write(JSONObject().put("id", id).put("error",
                                JSONObject().put("code", -32601)
                                    .put("message", "Android 客户端暂不支持服务端请求：" + method)))
                            events.emit(JSONObject().put("method", "local/unsupportedServerRequest")
                                .put("params", JSONObject().put("method", method)))
                        }
                    } else if (method == "serverRequest/resolved") {
                        val resolved = message.optJSONObject("params")?.opt("requestId")
                        prompts.value = prompts.value.filterNot { it.id.toString() == resolved.toString() }
                        events.emit(message)
                    } else {
                        events.emit(message)
                    }
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

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = sequence.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        waiting[id] = deferred
        try {
            write(JSONObject().put("method", method).put("id", id).put("params", params))
            val response = withTimeout(45_000L) { deferred.await() }
            if (response.has("error") && !response.isNull("error")) {
                val info = response.optJSONObject("error")
                throw IllegalStateException(method + "：" + (info?.optString("message") ?: "服务端错误"))
            }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            waiting.remove(id)
        }
    }

    suspend fun notify(method: String, params: JSONObject = JSONObject()) {
        write(JSONObject().put("method", method).put("params", params))
    }

    suspend fun answer(prompt: ServerPrompt, result: JSONObject) {
        write(JSONObject().put("id", prompt.id).put("result", result))
        prompts.value = prompts.value.filterNot { it.id.toString() == prompt.id.toString() }
    }

    private suspend fun write(message: JSONObject) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val output = writer ?: throw IllegalStateException("Codex 内核尚未连接")
                output.write(message.toString())
                output.newLine()
                output.flush()
            }
        }
    }

    fun close() {
        val current = process
        process = null
        writer = null
        current?.destroy()
        status.value = EngineStatus.Unavailable("连接已关闭")
        prompts.value = emptyList()
        failWaiting("连接已关闭")
    }
}
