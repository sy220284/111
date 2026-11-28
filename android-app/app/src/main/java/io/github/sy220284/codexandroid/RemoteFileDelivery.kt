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

internal data class RemoteDeliveryRequest(val thread: String, val binding: RemoteExecutionBinding,
    val target: String, val epoch: Long, val revision: Long) {
    fun owns(vm: CodexViewModel): Boolean =
        vm.activeThread.value == thread && vm.bridge.connectedSession.value == epoch &&
            vm.catalogWarmup.accountRevision.value == revision && !vm.threadSwitching.value &&
            !AppDataTransfer.state.value.busy && !AppDataTransfer.state.value.pendingRestore &&
            vm.conversationState.value.remote == binding && vm.remoteBindings.forThread(thread) == binding
}

/** Derive a non-display identity solely from an authenticated core response.
 * API keys and providers without stable session/workspace IDs use volatile checkpoints. */
internal object RemoteDeliveryAccountIdentity {
    fun key(account: JSONObject, sessions: JSONObject, thread: String,
        binding: RemoteExecutionBinding, relative: String): String? {
        val owner = AuthenticatedAccountIdentity.parse(account, sessions) ?: return null
        val identity = org.json.JSONArray().put("111-remote-delivery-v1").put(owner.user).put(owner.session).put(owner.workspace)
            .put(thread).put(binding.id).put(binding.endpoint).put(binding.cwd).put(relative).toString()
        return RemoteTransferCheckpoint.hex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)))
    }
}

/** Shared live owner/work-directory verification for every remote file operation. */
internal class RemoteWorkspaceAccess(private val vm: CodexViewModel, private val owner: RemoteDeliveryRequest) {
    fun owns() = owner.owns(vm)
    suspend fun request(method: String, params: JSONObject): JSONObject {
        check(owns()) { "远端会话或账号已变化" }
        val result = vm.bridge.request(method, params, expectedSession = owner.epoch, sendPermit = ::owns)
        check(owns()) { "远端会话或账号已变化" }
        return result
    }
    suspend fun confirmedRoot(): String {
        val binding = owner.binding
        vm.remoteBindings.ready(owner.epoch, binding, ::request)
        check(binding.matches(request("thread/read", JSONObject().put("threadId", owner.thread)).getJSONObject("thread"))) {
            "核心未确认会话的远端工作区"
        }
        val root = request("environment/info", JSONObject().put("environmentId", binding.id)).getString("cwd")
        check(RemoteExecutionBinding.nativePath(root) == binding.cwd) { "远端工作区已变化" }
        return root
    }
}

internal data class RemoteDirectoryEntry(val name: String, val relative: String?, val kind: String)

internal object RemoteDirectory {
    const val METHOD = "android/remoteFile/listDirectory"
    fun relative(binding: RemoteExecutionBinding, target: String): String =
        if (target.isEmpty()) "" else RemoteFileDelivery.selection(binding, target).relative

    fun decode(binding: RemoteExecutionBinding, requested: String, result: JSONObject): List<RemoteDirectoryEntry> {
        val returned = RemoteExecutionBinding.nativePath(result.getString("pathUri"))
        check(result.getString("environmentId") == binding.id &&
            (if (requested.isEmpty()) returned == binding.cwd else relative(binding, returned) == requested)) {
            "远端目录回执归属不一致"
        }
        val array = result.getJSONArray("entries")
        require(array.length() <= 4096) { "远端目录超过浏览容量" }
        val seen = mutableSetOf<String>()
        var bytes = 0
        val separator = if (binding.cwd.startsWith("\\\\") || binding.cwd.getOrNull(1) == ':') "\\" else "/"
        return (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            val name = entry.getString("name")
            val kind = entry.getString("kind")
            bytes += name.toByteArray(Charsets.UTF_8).size
            require(name.isNotEmpty() && name != "." && name != ".." && name.length <= 4096 &&
                '/' !in name && name.none(Char::isISOControl) && seen.add(name) &&
                kind in setOf("file", "directory", "other") && bytes <= 512 * 1024) { "远端目录回执无效" }
            val candidate = if (requested.isEmpty()) name else requested + separator + name
            // Links and unrepresentable names are visible but never actionable.
            val safe = if (kind == "other" || '\\' in name) null else try {
                RemoteFileDelivery.selection(binding, candidate).relative
            } catch (_: IllegalArgumentException) { null }
            RemoteDirectoryEntry(name, safe, kind)
        }.sortedWith(compareBy<RemoteDirectoryEntry> { it.kind != "directory" }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.name })
    }

    suspend fun read(vm: CodexViewModel, owner: RemoteDeliveryRequest): List<RemoteDirectoryEntry> {
        val path = relative(owner.binding, owner.target)
        val access = RemoteWorkspaceAccess(vm, owner)
        val root = access.confirmedRoot()
        val result = access.request(METHOD, JSONObject().put("environmentId", owner.binding.id)
            .put("workspaceUri", root).put("relativePath", path))
        return withContext(Dispatchers.Default) { decode(owner.binding, path, result) }.also {
            check(access.owns()) { "远端会话或账号已变化" }
        }
    }
}

internal object RemoteFileDelivery {
    const val METHOD = "android/remoteFile/readChunk"
    private val lock = Mutex()
    private var checkpoint: RemoteTransferCheckpoint? = null
    data class Selection(val relative: String, val filename: String)
    data class Payload(val name: String, val size: Long, val sha256: String,
        internal val entry: RemoteTransferCheckpoint.Entry, internal val request: RemoteDeliveryRequest) {
        val durableResume: Boolean get() = entry.source.resumeKey != null
    }
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
        val ownerRequest = RemoteDeliveryRequest(thread, binding, target, epoch, revision)
        val access = RemoteWorkspaceAccess(vm, ownerRequest)
        fun owns() = access.owns()
        suspend fun request(method: String, params: JSONObject) = access.request(method, params)
        val root = access.confirmedRoot()
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
        val account = request("account/read", JSONObject())
        val durableKey = if (account.optJSONObject("account")?.optString("type") == "chatgpt" &&
            (account.opt("userId") as? String)?.isNotBlank() == true) {
            val sessions = try { request("accountSession/list", CatalogRequestContract.accountSessions()) }
            catch (failure: RpcServerRejection) {
                if (failure.code.toInt() == -32601) null else throw failure
            }
            sessions?.let { RemoteDeliveryAccountIdentity.key(account, it, thread, binding, selected.relative) }
        } else null
        val owner = org.json.JSONArray().put(epoch).put(revision).put(thread).put(binding.id).put(binding.endpoint)
            .put(binding.cwd).put(selected.relative).toString()
        check(owns()) { "远端会话或账号已变化" }
        val store = checkpoint ?: RemoteTransferCheckpoint(File(cacheDir, "remote-delivery")).also { checkpoint = it }
        val entry = withContext(Dispatchers.IO) {
            check(owns()) { "远端会话或账号已变化" }
            store.begin(RemoteTransferCheckpoint.Source(owner, size, sha, durableKey))
        }
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
            withContext(Dispatchers.IO) {
                check(owns()) { "远端会话或账号已变化" }
                store.append(entry, offset, bytes)
            }
            onProgress(entry.offset, size)
        }
        withContext(Dispatchers.IO) {
            val ctx = coroutineContext
            store.verify(entry) { ctx.ensureActive(); check(owns()) { "远端会话或账号已变化" } }
        }
        check(owns()) { "远端会话已变化" }
        Payload(selected.filename, size, sha, entry, ownerRequest)
    }

    suspend fun save(vm: CodexViewModel, resolver: ContentResolver, destination: Uri, payload: Payload,
        onVerified: suspend (Long) -> Unit = {}, onWritten: suspend () -> Unit = {}) = lock.withLock {
        AppDataTransfer.withLocalMutation {
        val ctx = coroutineContext
        fun permit() {
            ctx.ensureActive()
            check(payload.request.owns(vm)) { "文件交付授权已变化，须重新读取" }
        }
        VerifiedDocumentDelivery.saveNewDocument(resolver, destination, payload.entry.file, payload.size,
            expectedHash = payload.sha256, permit = ::permit,
            beforeWrite = { requireNotNull(checkpoint).verify(payload.entry, ::permit) },
            onWritten = { withContext(Dispatchers.Main.immediate) { permit(); onWritten() } },
            onVerified = { count -> withContext(Dispatchers.Main.immediate) { permit(); onVerified(count) } })
    }
    }
}
