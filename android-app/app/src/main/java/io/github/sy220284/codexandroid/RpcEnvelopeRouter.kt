package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Protocol-envelope classification shared by runtime transport and pure unit tests. */
internal enum class RpcEnvelopeKind {
    Response, MalformedResponse, HostRequest, UnsupportedHostRequest, RequestResolved, Event
}

/**
 * All 8 official app-server Host requests are explicitly accounted for.
 *
 * The three gated methods require host-managed capabilities this Android
 * client does not advertise: dynamic tools, externally supplied auth tokens
 * and device attestation. Never pretend to execute them without a provider.
 */
internal object RpcEnvelopeRouter {
    internal val supportedHostRequests = setOf(
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/permissions/requestApproval",
        "item/tool/requestUserInput",
        "mcpServer/elicitation/request"
    )
    internal val gatedHostRequests = setOf(
        "item/tool/call",
        "account/chatgptAuthTokens/refresh",
        "attestation/generate"
    )
    internal val officialHostRequests = supportedHostRequests + gatedHostRequests

    init {
        require(supportedHostRequests.intersect(gatedHostRequests).isEmpty())
        require(officialHostRequests.size == 8)
    }

    fun unsupportedReason(method: String): String = when (method) {
        "item/tool/call" -> "此内核请求宿主工具执行，但当前连接未注册可执行的宿主工具"
        "account/chatgptAuthTokens/refresh" -> "此内核请求外部令牌刷新，当前账号使用内核管理的登录方式"
        "attestation/generate" -> "此内核请求设备证明，当前连接未配置设备证明服务"
        else -> "Android 客户端尚未实现服务端请求：$method"
    }

    fun classify(message: JSONObject): RpcEnvelopeKind {
        val id = message.opt("id")
        val method = message.optString("method")
        return when {
            method.isEmpty() && (message.has("result") || message.has("error") ||
                (id != null && id != JSONObject.NULL)) ->
                if (id is Number) RpcEnvelopeKind.Response else RpcEnvelopeKind.MalformedResponse
            method.isNotEmpty() && id != null && id != JSONObject.NULL ->
                if (method in supportedHostRequests) RpcEnvelopeKind.HostRequest
                else RpcEnvelopeKind.UnsupportedHostRequest
            method == "serverRequest/resolved" -> RpcEnvelopeKind.RequestResolved
            else -> RpcEnvelopeKind.Event
        }
    }
}
