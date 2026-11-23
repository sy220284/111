package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * Thread goals belong to the active conversation.
 * A collapsed advanced-budget control preserves a previously configured limit.
 * Editing the visible budget explicitly changes or clears it.
 */
internal object ThreadGoalRequestContract {
    private val statuses = setOf("active", "paused", "blocked", "complete")

    fun set(
        threadId: String,
        objective: String,
        status: String,
        budgetText: String,
        editBudget: Boolean,
        existingBudget: Boolean
    ): JSONObject {
        require(threadId.isNotBlank()) { "请先打开任务会话" }
        require(objective.trim().isNotEmpty()) { "请填写任务目标" }
        require(status in statuses) { "未知任务状态" }
        return JSONObject()
            .put("threadId", threadId)
            .put("origin", "user")
            .put("objective", objective.trim())
            .put("status", status)
            .apply {
                if (editBudget) {
                    val input = budgetText.trim()
                    if (input.isNotEmpty()) {
                        val amount = input.toLongOrNull()
                        require(amount != null && amount > 0) { "Token 上限必须是正整数" }
                        put("tokenBudget", amount)
                    } else if (existingBudget) {
                        put("tokenBudget", JSONObject.NULL)
                    }
                }
            }
    }
}
