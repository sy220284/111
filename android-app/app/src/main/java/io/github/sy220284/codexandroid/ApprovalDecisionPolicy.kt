package io.github.sy220284.codexandroid

/** Strongly typed UI decisions. Wire protocol strings are owned by this policy. */
internal enum class ApprovalAction {
    Decline, Cancel, AllowOnce, AllowSession, GrantTurn
}

/**
 * The UI cannot accidentally expand the permission scope or submit an invalid
 * answer while changing a button caption or rearranging the approval sheet.
 */
internal object ApprovalDecisionPolicy {
    fun resolve(prompt: ServerPrompt, action: ApprovalAction): String =
        when (prompt.method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                when (action) {
                    ApprovalAction.Decline -> "decline"
                    ApprovalAction.Cancel -> "cancel"
                    ApprovalAction.AllowOnce -> "accept"
                    ApprovalAction.AllowSession -> {
                        require(prompt.params.optString("kind") != "humanCommand") {
                            "人工执行命令只能逐次授权"
                        }
                        "acceptForSession"
                    }
                    ApprovalAction.GrantTurn ->
                        throw IllegalArgumentException("命令或文件操作不接受额外权限授权")
                }
            "item/permissions/requestApproval" -> when (action) {
                ApprovalAction.Decline -> "decline"
                ApprovalAction.GrantTurn -> "accept"
                else -> throw IllegalArgumentException("额外权限只能本轮允许或拒绝")
            }
            "mcpServer/elicitation/request" -> when (action) {
                ApprovalAction.Decline -> "decline"
                ApprovalAction.Cancel -> "cancel"
                else -> throw IllegalArgumentException("MCP 授权需通过明确的表单提交")
            }
            else -> throw IllegalArgumentException(
                "这类服务端请求没有通用的授权决策：" + prompt.method)
        }
}
