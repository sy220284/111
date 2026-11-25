package io.github.sy220284.codexandroid

import android.content.SharedPreferences
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** A remote path is never resolved by java.io.File on Android. */
data class RemoteExecutionBinding(val id: String, val endpoint: String, val cwd: String) {
    init {
        require(ExecutionEnvironmentConnectionPolicy.supportsRemoteName(id))
        require(ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint) && URI(endpoint).rawQuery == null) {
            "远端任务使用不含令牌或查询参数的连接地址"
        }
        require(cwd.startsWith("/") || Regex("^[A-Za-z]:[/\\\\].*").matches(cwd) || cwd.startsWith("\\\\")) {
            "远端工作目录必须为绝对路径"
        }
    }

    fun environments(): JSONArray = JSONArray().put(JSONObject().put("environmentId", id)
        .put("cwd", cwd).put("runtimeWorkspaceRoots", JSONArray().put(cwd)))

    fun apply(params: JSONObject): JSONObject = params.put("environments", environments())

    fun matches(thread: JSONObject): Boolean {
        val selected = thread.optJSONArray("environments") ?: return false
        val entry = selected.optJSONObject(0) ?: return false
        val roots = entry.optJSONArray("runtimeWorkspaceRoots") ?: return false
        return selected.length() == 1 && entry.optString("environmentId") == id &&
            entry.optString("cwd") == cwd && roots.length() == 1 && roots.optString(0) == cwd
    }

    fun requireTextOnly(images: List<String>, references: List<ComposerReference>) {
        require(images.isEmpty() && references.isEmpty()) { "远端任务请使用文字指令和远端文件路径，手机附件不会自动传输" }
    }

    internal fun json() = JSONObject().put("id", id).put("endpoint", endpoint).put("cwd", cwd)

    companion object {
        internal fun read(json: JSONObject) = RemoteExecutionBinding(json.getString("id"), json.getString("endpoint"), json.getString("cwd"))

        /** EnvironmentInfoResponse.cwd is a file URI; environment selections use native strings. */
        internal fun nativePath(raw: String): String {
            val uri = URI(raw)
            require(uri.scheme == "file" && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawUserInfo == null && uri.port == -1) { "远端未报告可用工作目录" }
            val path = requireNotNull(uri.path)
            require(path.startsWith("/") && !path.contains('\u0000') &&
                path.split('/').none { it == "." || it == ".." } &&
                !path.contains('\\')) { "远端工作目录格式不受支持" }
            val authority = uri.host
            return when {
                authority != null -> "\\\\" + authority + path.replace('/', '\\')
                !uri.rawAuthority.isNullOrEmpty() -> error("远端文件 URI 主机格式不受支持")
                Regex("^/[A-Za-z]:/.*").matches(path) -> path.drop(1).replace('/', '\\')
                else -> path
            }
        }

        internal fun hasRemote(thread: JSONObject): Boolean {
            val selected = thread.optJSONArray("environments") ?: return false
            return (0 until selected.length()).any {
                selected.optJSONObject(it)?.optString("environmentId")?.let { id -> id.isNotBlank() && id != "local" } == true
            }
        }
    }
}

/** Per-thread durable binding survives native-process restart; no implicit local fallback. */
internal class RemoteExecutionBindings(private val preferences: SharedPreferences) {
    fun forThread(threadId: String?): RemoteExecutionBinding? = threadId?.let {
        preferences.getString("thread:$it", null)?.let { value -> RemoteExecutionBinding.read(JSONObject(value)) }
    }

    fun permitsRegistration(id: String, endpoint: String): Boolean =
        preferences.all.filterKeys { it.startsWith("thread:") }.values.none {
            val binding = RemoteExecutionBinding.read(JSONObject(it as String))
            binding.id == id && binding.endpoint != endpoint
        }

    fun bind(thread: String, binding: RemoteExecutionBinding) {
        require(thread.isNotBlank() && permitsRegistration(binding.id, binding.endpoint))
        require(forThread(thread)?.let { it == binding } != false) { "会话远端归属不能覆盖" }
        check(preferences.edit().putString("thread:$thread", binding.json().toString()).commit()) {
            "远端会话归属无法保存"
        }
    }

    suspend fun ready(binding: RemoteExecutionBinding, request: suspend (String, JSONObject) -> JSONObject) {
        val params = JSONObject().put("environmentId", binding.id)
        val status = request("environment/status", params)
        if (status.optString("status") == "unknown") {
            request("environment/add", JSONObject().put("environmentId", binding.id).put("execServerUrl", binding.endpoint))
        }
        val info = request("environment/info", params)
        require(RemoteExecutionBinding.nativePath(info.getString("cwd")) == binding.cwd) {
            "远端默认工作目录已变化，请重新连接并创建对应任务"
        }
        val checked = request("environment/status", params)
        check(checked.optString("status") == "ready") { "远端未连接：" + checked.optString("error", "请检查执行服务") }
    }

    suspend fun ensureThread(id: String, binding: RemoteExecutionBinding, running: Boolean,
                             request: suspend (String, JSONObject) -> JSONObject) {
        ready(binding, request)
        val params = JSONObject().put("threadId", id)
        val thread = request("thread/read", params).getJSONObject("thread")
        if (binding.matches(thread)) return
        check(!running) { "正在执行的远端回合归属无法确认，请先停止任务" }
        request("thread/settings/update", binding.apply(JSONObject().put("threadId", id)))
        check(binding.matches(request("thread/read", params).getJSONObject("thread"))) {
            "运行核心未确认远端环境归属，任务未发送"
        }
    }
}
