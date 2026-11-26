package io.github.sy220284.codexandroid

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

internal object RemoteFileDelivery {
    const val METHOD = "android/remoteFile/read"
    const val MAX_BYTES = 8 * 1024 * 1024
    data class Selection(val relative: String, val filename: String)
    data class Payload(val name: String, val bytes: ByteArray, val sha256: String)
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
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    suspend fun load(vm: CodexViewModel, thread: String, binding: RemoteExecutionBinding,
        target: String, epoch: Long, revision: Long): Payload {
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
        val result = request(METHOD, JSONObject().put("environmentId", binding.id)
            .put("workspaceUri", root).put("relativePath", selected.relative))
        check(result.getString("environmentId") == binding.id) { "文件回执的环境归属不一致" }
        val reported = selection(binding, RemoteExecutionBinding.nativePath(result.getString("pathUri")))
        check(reported == selected) { "文件回执的路径不一致" }
        return withContext(Dispatchers.Default) {
            val encoded = result.getString("dataBase64")
            require(encoded.length <= ((MAX_BYTES + 2) / 3) * 4) { "文件超过 8 MiB" }
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size <= MAX_BYTES)
            Payload(selected.filename, bytes, hash(bytes))
        }.also { check(owns()) { "远端会话已变化" } }
    }
    suspend fun save(resolver: ContentResolver, destination: Uri, payload: Payload) = withContext(Dispatchers.IO) {
        require(destination.scheme == "content") { "请选择系统文档保存位置" }
        requireNotNull(resolver.openOutputStream(destination, "wt")) { "无法打开保存位置" }.use {
            it.write(payload.bytes); it.flush()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0
        requireNotNull(resolver.openInputStream(destination)) { "无法回读保存文件" }.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= payload.bytes.size) { "保存结果大小不符" }
                digest.update(buffer, 0, count)
            }
        }
        check(total == payload.bytes.size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == payload.sha256) {
            "保存后文件校验失败"
        }
    }
}
