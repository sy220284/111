package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Authentication actions are derived from the locked Codex MCP status contract. */
internal enum class McpAuthAction { None, ChatGptAccount, OAuthLogin, EngineConfiguration }

internal data class McpAuthPresentation(
    val state: String,
    val explanation: String?,
    val action: McpAuthAction,
    val actionLabel: String?
)

/**
 * Hosted codex_apps uses the Codex ChatGPT session, not per-server OAuth/DCR.
 * Third-party services may start OAuth only when the server explicitly reports notLoggedIn.
 */
internal object McpServiceAuthPolicy {
    fun presentation(server: JSONObject): McpAuthPresentation {
        if (server.optString("name") == "codex_apps") {
            return McpAuthPresentation(
                state = "ChatGPT 账号",
                explanation = "此服务使用当前 ChatGPT 账号。连接异常时请核对账号并刷新服务状态。",
                action = McpAuthAction.ChatGptAccount,
                actionLabel = "查看账号"
            )
        }
        return when (server.optString("authStatus")) {
            "notLoggedIn" -> McpAuthPresentation(
                "需要授权", null, McpAuthAction.OAuthLogin, "授权登录"
            )
            "oAuth" -> McpAuthPresentation("已授权", null, McpAuthAction.None, null)
            "bearerToken" -> McpAuthPresentation("已配置令牌", null, McpAuthAction.None, null)
            "unsupported" -> McpAuthPresentation(
                "不支持网页授权", "如需更改服务地址或凭据，请前往引擎配置。",
                McpAuthAction.EngineConfiguration, "查看配置"
            )
            "unknown" -> McpAuthPresentation(
                "授权状态待确认", "请刷新连接状态后再操作。", McpAuthAction.None, null
            )
            else -> McpAuthPresentation(
                "授权状态未识别", "请刷新连接状态后再操作。", McpAuthAction.None, null
            )
        }
    }

    fun oauthLoginParams(server: JSONObject, activeThread: String?): JSONObject {
        require(presentation(server).action == McpAuthAction.OAuthLogin) {
            "该服务当前无需发起单独的 OAuth 授权"
        }
        val name = server.optString("name")
        require(name.isNotBlank()) { "缺少 MCP 服务名称" }
        return JSONObject().put("name", name).apply {
            if (server.has("environmentId") && !server.isNull("environmentId")) {
                val environmentId = server.optString("environmentId")
                if (environmentId.isNotBlank()) put("environmentId", environmentId)
            }
            if (!activeThread.isNullOrBlank()) put("threadId", activeThread)
        }
    }

    /** A completion may arrive from another thread or an earlier authorization attempt. */
    fun matchesCompletion(startResponse: JSONObject?, notification: JSONObject): Boolean {
        val startedId = startResponse?.optString("loginId").orEmpty()
        return startedId.isNotBlank() && startedId == notification.optString("loginId")
    }
}
