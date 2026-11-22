package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Protocol-envelope classification shared by runtime transport and pure unit tests. */
internal enum class RpcEnvelopeKind {
    Response, HostRequest, UnsupportedHostRequest, RequestResolved, Event
}

internal object RpcEnvelopeRouter {
    private val supportedHostRequests = setOf(
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/permissions/requestApproval",
        "item/tool/requestUserInput",
        "mcpServer/elicitation/request"
    )

    fun classify(message: JSONObject): RpcEnvelopeKind {
        val id = message.opt("id")
        val method = message.optString("method")
        return when {
            id is Number && method.isEmpty() -> RpcEnvelopeKind.Response
            method.isNotEmpty() && id != null && id != JSONObject.NULL ->
                if (method in supportedHostRequests) RpcEnvelopeKind.HostRequest
                else RpcEnvelopeKind.UnsupportedHostRequest
            method == "serverRequest/resolved" -> RpcEnvelopeKind.RequestResolved
            else -> RpcEnvelopeKind.Event
        }
    }
}
