package io.github.sy220284.codexandroid

/**
 * Host-initiated methods must only be answered with JSON-RPC result/error by id.
 * The CI protocol verifier compares this exact list against Codex's pinned
 * server_request_definitions macro, so upstream method additions require review.
 */
internal object ProtocolDirectionPolicy {
    private val HOST_ONLY = setOf(
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/tool/requestUserInput",
        "mcpServer/elicitation/request",
        "item/permissions/requestApproval",
        "item/tool/call",
        "account/chatgptAuthTokens/refresh",
        "attestation/generate"
    )

    fun requireClientSend(method: String) {
        require(method !in HOST_ONLY) {
            "这是 Codex 核心发往 Android 的主动请求，不能由客户端反向发起：$method"
        }
    }
}
