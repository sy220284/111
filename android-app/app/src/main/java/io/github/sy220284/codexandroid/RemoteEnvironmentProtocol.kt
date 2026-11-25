package io.github.sy220284.codexandroid

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** environment/add replaces the existing executor. Preserve the actual registration receipt
 * and reuse it only in the same core session and for the same endpoint. */
internal class RemoteEnvironmentProtocol {
    private val mutex = Mutex()
    private var epoch: Long? = null
    private val registrations = mutableMapOf<String, Pair<String, String>>()

    suspend fun register(session: Long, id: String, endpoint: String,
                         request: suspend (String, JSONObject) -> JSONObject,
                         pin: () -> Unit): JSONObject = mutex.withLock {
        if (epoch != session) { registrations.clear(); epoch = session }
        val known = registrations[id]
        require(known == null || known.first == endpoint) { "该环境名称已绑定其他设备" }
        val status = request("environment/status", JSONObject().put("environmentId", id)).getString("status")
        if (known != null && status != "unknown") {
            pin()
            return@withLock JSONObject(known.second)
        }
        check(status == "unknown") { "该名称已由其他连接登记，请选择新名称" }
        val reply = request("environment/add", JSONObject().put("environmentId", id).put("execServerUrl", endpoint))
        registrations[id] = endpoint to reply.toString()
        pin()
        JSONObject(reply.toString())
    }

    companion object {
        suspend fun verifyReady(binding: RemoteExecutionBinding, request: suspend (String, JSONObject) -> JSONObject) {
            val params = JSONObject().put("environmentId", binding.id)
            val info = request("environment/info", params)
            require(RemoteExecutionBinding.nativePath(info.getString("cwd")) == binding.cwd) {
                "远端默认工作目录已变化，请重新连接并创建对应任务"
            }
            val checked = request("environment/status", params)
            check(checked.optString("status") == "ready") { "远端未连接：" + checked.optString("error", "请检查执行服务") }
        }

        suspend fun confirmThread(id: String, binding: RemoteExecutionBinding, running: Boolean,
                                  request: suspend (String, JSONObject) -> JSONObject) {
            val params = JSONObject().put("threadId", id)
            if (binding.matches(request("thread/read", params).getJSONObject("thread"))) return
            check(!running) { "正在执行的远端回合归属无法确认，请先停止任务" }
            request("thread/settings/update", binding.apply(JSONObject().put("threadId", id)))
            check(binding.matches(request("thread/read", params).getJSONObject("thread"))) {
                "运行核心未确认远端环境归属，任务未发送"
            }
        }
    }
}
