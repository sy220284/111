package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact result envelope for app-server initiated requests.
 * A denied additional-permissions request contains an empty granted profile.
 */
object ApprovalProtocol {
    fun result(prompt: ServerPrompt, decision: String, answers: Map<String, String> = emptyMap()): JSONObject {
        return when (prompt.method) {
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                val choices = if (prompt.params.optString("kind") == "humanCommand") {
                    setOf("accept", "decline", "cancel")
                } else setOf("accept", "acceptForSession", "decline", "cancel")
                require(decision in choices) { "该请求不允许使用 " + decision + " 决策" }
                JSONObject().put("decision", decision)
            }
            "item/permissions/requestApproval" -> {
                require(decision in setOf("accept", "decline")) { "权限请求只允许本轮授权或拒绝" }
                val requested = prompt.params.optJSONObject("permissions")
                    ?: throw IllegalArgumentException("缺少权限范围")
                val granted = if (decision == "accept") {
                    // Only return a requested capability; never expand scope.
                    JSONObject(requested.toString())
                } else JSONObject()
                JSONObject().put("permissions", granted).put("scope", "turn")
            }
            "item/tool/requestUserInput" -> {
                require(decision == "submit") { "回答请求必须完整提交" }
                val questions = prompt.params.optJSONArray("questions") ?: JSONArray()
                require(questions.length() > 0) { "缺少待回答的问题" }
                val resolved = JSONObject()
                for (i in 0 until questions.length()) {
                    val id = questions.getJSONObject(i).getString("id")
                    val answer = answers[id]?.trim()
                    require(!answer.isNullOrEmpty()) { "问题尚未回答：" + id }
                    resolved.put(id, JSONObject().put("answers", JSONArray().put(answer)))
                }
                JSONObject().put("answers", resolved)
            }
            "mcpServer/elicitation/request" -> {
                require(decision in setOf("decline", "cancel")) {
                    "MCP 外部授权需要匹配表单类型，当前仅支持拒绝/取消"
                }
                JSONObject().put("action", decision).put("content", JSONObject.NULL)
            }
            else -> throw IllegalArgumentException("缺少此类服务端请求的契约：" + prompt.method)
        }
    }
}
