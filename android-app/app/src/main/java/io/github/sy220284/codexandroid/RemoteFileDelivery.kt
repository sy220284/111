package io.github.sy220284.codexandroid

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object RemoteFileDelivery {
    const val METHOD = "android/remoteFile/readChunk"
    private val lock = Mutex()
    private var checkpoint: RemoteTransferCheckpoint? = null
    data class Selection(val relative: String, val filename: String)
    data class Payload(val name: String, val size: Long, val sha256: String, internal val entry: RemoteTransferCheckpoint.Entry)
    fun selection(binding: RemoteExecutionBinding, target: String): Selection {
        require(target.length in 1..4096 && target.none(Char::isISOControl)) { "无效远端文件路径" }
        val windows = binding.cwd.startsWith("\\\\") || Regex("^[A-Za-z]:[/\\\\].*").matches(binding.cwd)
        fun normalize(value: String) = if (windows) value.replace('\\', '/') else value
        val base = normalize(binding.cwd).trimEnd('/')
        val path = normalize(target)
        val absolute = path.startsWith('/') || Regex("^[A-Za-z]:.*").matches(path)
        val relative = if (absolute) {
            require(path.startsWith("$base/", ignoreCase = windows)) { "文件不在绑定的远端工作区" }
            path.substring(base.length + 1)
        } else path
        val parts = relative.split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() && it != "." && it != ".." &&
            ':' !in it && '\\' !in it && (!windows || (it.last() !in " ." && it.none { c -> c in "<>\"|?*" })) }) {
            "远端文件路径不安全"
        }
        return Selection(parts.joinToString(if (windows) "\\" else "/"), parts.last())
    }
    suspend fun load(vm: CodexViewModel, thread: String, binding: RemoteExecutionBinding,
        target: String, epoch: Long, revision: Long, cacheDir: File,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }): Payload = lock.withLock {
        val selected = selection(binding, target)
        fun owns() = vm.activeThread.value == thread && vm.bridge.connectedSession.value == epoch &&
            vm.catalogWarmup.accountRevision.value == revision && vm.remoteBindings.forThread(thread) == binding &&
            !vm.threadSwitching.value
        suspend fun request(method: String, params: JSONObject): JSONObject {
            check(owns()) { "远端会话或账号已变化" }
            val result = vm.bridge.request(method, params, expectedSession = epoch, sendPermit = ::owns)
            check(owns()) { "远端会话或账号已变化" }
            return result
        }
        vm.remoteBindings.ready(epoch, binding, ::request)
        check(binding.matches(request("thread/read", JSONObject().put("threadId", thread)).getJSONObject("thread"))) {
            "核心未确认会话的远端工作区"
        }
        val info = request("environment/info", JSONObject().put("environmentId", binding.id))
        val root = info.getString("cwd")
        check(RemoteExecutionBinding.nativePath(root) == binding.cwd) { "远端工作区已变化" }
        suspend fun chunk(offset: Long, version: String?): JSONObject {
            val result = request(METHOD, JSONObject().put("environmentId", binding.id)
                .put("workspaceUri", root).put("relativePath", selected.relative)
                .put("offset", offset).put("version", version ?: JSONObject.NULL))
            check(result.getString("environmentId") == binding.id &&
                selection(binding, RemoteExecutionBinding.nativePath(result.getString("pathUri"))) == selected &&
                result.getLong("offset") == offset) { "远端分块回执归属不一致" }
            return result
        }
        fun decode(result: JSONObject, expected: Long): ByteArray =
            RemoteTransferCheckpoint.decodeChunk(result.getString("dataBase64"), expected)
        val first = chunk(0, null)
        val size = first.getLong("size")
        val sha = first.getString("sha256")
        val version = first.getString("version")
        require(size in 0..RemoteTransferCheckpoint.MAX_BYTES && RemoteTransferCheckpoint.isDigest(sha) &&
            RemoteTransferCheckpoint.isDigest(version)) { "远端文件大小或摘要无效" }
        val owner = org.json.JSONArray().put(revision).put(thread).put(binding.id).put(binding.endpoint)
            .put(binding.cwd).put(selected.relative).toString()
        val store = checkpoint ?: RemoteTransferCheckpoint(File(cacheDir, "remote-delivery")).also { checkpoint = it }
        val entry = withContext(Dispatchers.IO) { store.begin(RemoteTransferCheckpoint.Source(owner, size, sha)) }
        val firstBytes = withContext(Dispatchers.Default) {
            decode(first, minOf(size, RemoteTransferCheckpoint.CHUNK_BYTES.toLong()))
        }
        onProgress(entry.offset, size)
        while (entry.offset < size) {
            coroutineContext.ensureActive()
            check(owns()) { "远端会话已变化" }
            val offset = entry.offset
            val bytes = if (offset == 0L) firstBytes else {
                val result = chunk(offset, version)
                check(result.getLong("size") == size && result.getString("version") == version &&
                    result.isNull("sha256")) { "远端源文件已变化" }
                withContext(Dispatchers.Default) {
                    decode(result, minOf(size - offset, RemoteTransferCheckpoint.CHUNK_BYTES.toLong()))
                }
            }
            withContext(Dispatchers.IO) { store.append(entry, offset, bytes) }
            onProgress(entry.offset, size)
        }
        withContext(Dispatchers.IO) {
            val ctx = coroutineContext
            store.verify(entry) { ctx.ensureActive() }
        }
        check(owns()) { "远端会话已变化" }
        Payload(selected.filename, size, sha, entry)
    }

    suspend fun save(resolver: ContentResolver, destination: Uri, payload: Payload,
        onVerified: suspend (Long) -> Unit = {}, onWritten: suspend () -> Unit = {}) = lock.withLock {
        withContext(Dispatchers.IO) {
            require(destination.scheme == "content") { "请选择系统文档保存位置" }
            val ctx = coroutineContext
            requireNotNull(checkpoint).verify(payload.entry) { ctx.ensureActive() }
            requireNotNull(resolver.openOutputStream(destination, "wt")) { "无法打开保存位置" }.use { output ->
                payload.entry.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            }
            withContext(Dispatchers.Main.immediate) { onWritten() }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastReport = 0L
            requireNotNull(resolver.openInputStream(destination)) { "无法回读保存文件" }.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= payload.size) { "保存结果大小不符" }
                    digest.update(buffer, 0, count)
                    if (total - lastReport >= 256 * 1024 || total == payload.size) {
                        lastReport = total
                        withContext(Dispatchers.Main.immediate) { onVerified(total) }
                    }
                }
            }
            check(total == payload.size && RemoteTransferCheckpoint.hex(digest.digest()) == payload.sha256) {
                "保存后文件校验失败"
            }
        }
    }
}
