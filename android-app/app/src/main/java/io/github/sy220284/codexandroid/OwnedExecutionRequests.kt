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
) = remoteBindings.ensureThread(id, binding, running) { method, params ->
    bridge.request(method, params, expectedSession = expectedSession)
}

internal suspend fun CodexViewModel.dispatchOwnedRequest(
    method: String, params: JSONObject?, epoch: Long
): JSONObject {
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
