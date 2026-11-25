package io.github.sy220284.codexandroid

import org.json.JSONObject

internal fun accountScopedRead(method: String): Boolean = method in setOf(
    "model/list", "modelProvider/list", "skills/list", "plugin/list", "plugin/installed",
    "plugin/search", "plugin/read", "app/list", "mcpServerStatus/list",
    "config/read", "configRequirements/read", "permissionProfile/list"
)

internal suspend fun CodexViewModel.ensureRemoteThread(
    id: String, binding: RemoteExecutionBinding, running: Boolean = false,
    expectedSession: Long = requireNotNull(bridge.connectedSession.value) { "运行核心尚未连接" }
) {
    val request: suspend (String, JSONObject) -> JSONObject = { method, params ->
        bridge.request(method, params, expectedSession = expectedSession)
    }
    remoteBindings.ready(expectedSession, binding, request)
    if (!running) {
        // Remote history may have been viewed without loading the execution thread.
        // Resume with sticky environments intact and never override cwd with a local path.
        request("thread/resume", JSONObject().put("threadId", id).put("excludeTurns", true))
    }
    RemoteEnvironmentProtocol.confirmThread(id, binding, running, request)
}

internal suspend fun CodexViewModel.dispatchOwnedRequest(
    method: String, params: JSONObject?, epoch: Long
): JSONObject {
    if (method == "environment/add" && params != null) {
        require(params.keys().asSequence().toSet() == setOf("environmentId", "execServerUrl")) { "当前客户端仅支持不含认证令牌的远端登记" }
        return remoteBindings.register(epoch, params.getString("environmentId"), params.getString("execServerUrl")) { name, payload ->
            bridge.request(name, payload, expectedSession = epoch)
        }
    }
    if (method != "thread/fork" || params == null)
        return bridge.request(method, params, expectedSession = epoch)
    val parent = params.getString("threadId")
    val binding = remoteBindings.forThread(parent)
        ?: return modelPreviews.request(method, params, expectedSession = epoch)
    ensureRemoteThread(parent, binding, expectedSession = epoch)
    val reply = bridge.request(method, params, expectedSession = epoch)
    val id = reply.getJSONObject("thread").getString("id")
    remoteBindings.bind(id, binding)
    ensureRemoteThread(id, binding, expectedSession = epoch)
    val confirmed = bridge.request("thread/read", JSONObject().put("threadId", id), expectedSession = epoch)
    return reply.put("thread", confirmed.getJSONObject("thread"))
}
