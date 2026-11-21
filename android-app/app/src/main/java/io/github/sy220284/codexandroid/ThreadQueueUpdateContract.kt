package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Official ThreadQueueUpdateParams: camelCase fields, exact queued submission ID. */
object ThreadQueueUpdateContract {
    fun canEditAsText(input: JSONArray): Boolean =
        input.length() == 1 && input.optJSONObject(0)?.optString("type") == "text"

    fun build(threadId: String, queuedSubmissionId: String, text: String): JSONObject {
        require(threadId.isNotBlank()) { "缺少当前会话" }
        require(queuedSubmissionId.isNotBlank()) { "缺少排队任务标识" }
        val content = text.trim()
        require(content.isNotEmpty()) { "任务内容不能为空" }
        return JSONObject()
            .put("threadId", threadId.trim())
            .put("queuedSubmissionId", queuedSubmissionId.trim())
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", content)))
    }
}
