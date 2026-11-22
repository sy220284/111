package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Serialize the pinned app-server approved-plan handshake. */
object PlanExecutionContract {
    fun prepare(threadId: String, markdown: String): JSONObject {
        require(threadId.isNotBlank()) { "批准计划需要当前会话" }
        require(markdown.trim().isNotEmpty()) { "没有可批准的计划正文" }
        return JSONObject().put("threadId", threadId).put("markdown", markdown)
    }

    fun prompt(response: JSONObject): String {
        val prompt = response.optString("prompt").trim()
        require(prompt.isNotEmpty()) { "内核没有返回已批准方案的执行指令" }
        return prompt
    }
}
