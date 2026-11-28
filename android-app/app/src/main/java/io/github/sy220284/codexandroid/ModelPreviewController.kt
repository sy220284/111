package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal data class ModelPreviewRequest(
    val id: String, val threadId: String, val epoch: Long, val accountRevision: Long,
    val workspace: String, val relativePath: String, val line: Int?
)

internal object ModelPreviewPolicy {
    const val MAX_BYTES = 8 * 1024 * 1024
    private fun JSONObject.keysSet() = keys().asSequence().toSet()
    fun target(args: JSONObject): Pair<String, Int?> {
        require(args.keysSet() == setOf("target"))
        val target = args.getJSONObject("target")
        require(target.keysSet() in setOf(setOf("type", "path"), setOf("type", "path", "line")))
        require(target.opt("type") == "file")
        val path = target.opt("path") as? String ?: error("路径必须为文本")
        require(path.length in 1..4096 && path.none { it.isISOControl() })
        val line = if (target.has("line")) {
            val value = target.get("line") as? Number ?: error("行号必须为正整数")
            val integer = value.toLong()
            require(integer in 1..Int.MAX_VALUE.toLong() && value.toDouble() == integer.toDouble())
            integer.toInt()
        } else null
        return path to line
    }
    fun thread(meta: JSONObject): String {
        val nested = when (val raw = meta.opt("x-codex-turn-metadata")) {
            is JSONObject -> raw
            is String -> JSONObject(raw)
            else -> null
        }
        val direct = (meta.opt("threadId") as? String)?.takeIf { it.isNotBlank() }
        val embedded = (nested?.opt("thread_id") as? String)?.takeIf { it.isNotBlank() }
        require(direct == null || embedded == null || direct == embedded)
        return requireNotNull(direct ?: embedded) { "缺少调用会话" }
    }
    fun resolve(root: File, workspace: String, target: String): File {
        val base = WorkspaceSelectionContract.validate(root, File(workspace))
        val candidate = File(target).let { if (it.isAbsolute) it else File(base, target) }
        val normalized = candidate.toPath().toAbsolutePath().normalize()
        require(normalized.startsWith(base.toPath()) && normalized != base.toPath()) { "文件不在任务工作区" }
        var child = base.toPath()
        for (part in base.toPath().relativize(normalized)) {
            child = child.resolve(part)
            require(!Files.isSymbolicLink(child)) { "预览不支持符号链接" }
        }
        require(normalized.toFile().canonicalFile == normalized.toFile() &&
            Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) { "文件已移动或不是普通文件" }
        require(Files.size(normalized) <= MAX_BYTES) { "文件超过 8 MB 预览限制" }
        return normalized.toFile()
    }
    fun read(root: File, request: ModelPreviewRequest): ByteArray {
        val file = resolve(root, request.workspace, request.relativePath)
        return Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "文件超过 8 MB 预览限制" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}

/** The model may only ask to show a file. Actual local reading requires the user's preview tap. */
internal class ModelPreviewController(private val vm: CodexViewModel, private val root: File) {
    private val state = MutableStateFlow<List<ModelPreviewRequest>>(emptyList())
    val requests = state.asStateFlow()
    private val failedState = MutableStateFlow<Map<String, String>>(emptyMap())
    val failures = failedState.asStateFlow()
    private var server: ModelPreviewMcpServer? = null
    private var closed = false
    private val lock = Any()

    private fun host(): ModelPreviewMcpServer = synchronized(lock) {
        check(!closed)
        server ?: ModelPreviewMcpServer(::queue).also { it.launch(); server = it }
    }

    private suspend fun configure(params: JSONObject, effective: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val endpoint = host().config()
        val existing = effective.optJSONObject("config")?.optJSONObject("mcp_servers")
            ?.optJSONObject(ModelPreviewMcpServer.NAME)
        require(existing == null || existing.optString("url") == endpoint.optString("url")) {
            "本机文件预览服务名称与现有 MCP 配置冲突"
        }
        val copy = JSONObject(params.toString())
        val config = copy.optJSONObject("config") ?: JSONObject()
        val key = "mcp_servers." + ModelPreviewMcpServer.NAME
        require(!config.has(key)) { "本机文件预览服务已配置" }
        copy.put("config", config.put(key, endpoint))
    }

    /** Only setup is optional. The primary RPC is dispatched exactly once and its errors propagate. */
    suspend fun request(method: String, params: JSONObject, effective: JSONObject? = null,
        expectedSession: Long? = vm.bridge.connectedSession.value, sendPermit: (() -> Boolean)? = null): JSONObject {
        require(method in setOf("thread/start", "thread/resume", "thread/fork"))
        val epoch = requireNotNull(expectedSession) { "运行核心尚未连接" }
        val owner = TaskRequestOwner(epoch, vm.catalogWarmup.accountRevision.value)
        val revision = owner.account
        fun owns() = owner.current(vm) && sendPermit?.invoke() != false
        return PreviewOptionalRequest.execute(params, prepare = {
            val configuration = effective ?: run {
                val explicit = (params.opt("cwd") as? String)?.takeIf { it.isNotBlank() }
                val cwd = explicit ?: owner.request(vm, "thread/read",
                    JSONObject().put("threadId", params.getString("threadId")).put("includeTurns", false))
                    .getJSONObject("thread").getString("cwd")
                val directory = WorkspaceSelectionContract.validate(root, File(cwd))
                owner.request(vm, "config/read", JSONObject().put("cwd", directory.absolutePath))
            }
            configure(params, configuration)
        }, dispatch = { owner.guarded({ owner.current(vm) }) { vm.bridge.request(method, it, sendPermit = ::owns, expectedSession = epoch) } }, unavailable = { receipt, failure ->
            // Log only the error category: configuration and transport exceptions can contain credentials.
            android.util.Log.w("ModelPreview", "预览服务准备失败：" + failure.javaClass.simpleName)
            val thread = receipt.optJSONObject("thread")?.optString("id").orEmpty()
            synchronized(lock) {
                if (thread.isNotBlank() && !closed &&
                    vm.bridge.connectedSession.value == epoch && vm.catalogWarmup.accountRevision.value == revision) {
                    failedState.value = failedState.value + (thread to
                        "任务文件预览暂不可用，当前会话仍可使用。任务结束后重新打开会话可重试预览服务。")
                }
            }
        })
    }

    // NanoHTTPD invokes this synchronous tool handler on its bounded loopback workers.
    // Keep the live native ownership check, without reading any conversation turns.
    private fun queue(args: JSONObject, meta: JSONObject): JSONObject = runBlocking {
        withTimeout(12000) {
            val (path, line) = ModelPreviewPolicy.target(args)
            val thread = ModelPreviewPolicy.thread(meta)
            val owner = vm.captureTaskOwner() ?: error("内核未连接")
            val epoch = owner.epoch
            val revision = owner.account
            check(vm.bridge.status.value == EngineStatus.Connected && !AppDataTransfer.state.value.busy)
            val native = owner.request(vm, "thread/read",
                JSONObject().put("threadId", thread).put("includeTurns", false), 9000L)
                .getJSONObject("thread")
            check(native.getString("id") == thread)
            check(ThreadActivityPolicy.isActive(ThreadActivityPolicy.fromThread(native)) ||
                thread in vm.threadExecutions.value) { "任务已结束" }
            val cwd = WorkspaceSelectionContract.validate(root, File(native.getString("cwd")))
            val file = ModelPreviewPolicy.resolve(root, cwd.absolutePath, path)
            val relative = file.relativeTo(cwd).invariantSeparatorsPath
            synchronized(lock) {
                check(!closed && vm.bridge.connectedSession.value == epoch &&
                    vm.catalogWarmup.accountRevision.value == revision && !AppDataTransfer.state.value.busy)
                val current = state.value.filter { it.epoch == epoch && it.accountRevision == revision }
                if (current.none { it.threadId == thread && it.workspace == cwd.absolutePath && it.relativePath == relative && it.line == line }) {
                    check(current.size < 32) { "文件预览队列已满" }
                    state.value = current + ModelPreviewRequest(UUID.randomUUID().toString(), thread, epoch, revision, cwd.absolutePath, relative, line)
                }
            }
            JSONObject().put("status", "queued").put("threadId", thread)
        }
    }
    fun startup(epoch: Long, params: JSONObject) = synchronized(lock) {
        if (vm.bridge.connectedSession.value == epoch) {
            val thread = (params.opt("threadId") as? String)?.takeIf { it.isNotBlank() } ?: return@synchronized
            failedState.value = when (params.optString("status")) {
                "failed", "cancelled" -> failedState.value + (thread to "任务文件预览服务未就绪，请在会话空闲时重新打开会话。")
                "starting", "ready" -> failedState.value - thread
                else -> failedState.value
            }
        }
    }
    fun dismiss(request: ModelPreviewRequest) = synchronized(lock) { state.value = state.value.filterNot { it.id == request.id } }
    fun removeThread(thread: String) = synchronized(lock) {
        state.value = state.value.filterNot { it.threadId == thread }; failedState.value = failedState.value - thread
    }
    fun clear() = synchronized(lock) { state.value = emptyList(); failedState.value = emptyMap() }
    fun close() {
        val old = synchronized(lock) { closed = true; state.value = emptyList(); failedState.value = emptyMap(); server.also { server = null } }
        old?.stop()
    }
}
