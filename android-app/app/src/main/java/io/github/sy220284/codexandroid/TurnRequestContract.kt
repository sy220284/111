package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON request adapter for the bundled Codex TurnStartParams and CollaborationMode types.
 * CollaborationMode.Settings is serialized by upstream Rust with snake_case fields,
 * while TurnStartParams itself is camelCase. Keep those two layers separate.
 */
object TurnRequestContract {
    /** Attach a new instruction to the current running turn, with an ID precondition. */
    fun steer(threadId: String, expectedTurnId: String, text: String): JSONObject {
        require(threadId.isNotBlank()) { "缺少当前会话" }
        require(expectedTurnId.isNotBlank()) { "任务尚未取得运行轮次" }
        val instruction = text.trim()
        require(instruction.isNotEmpty()) { "补充指令不能为空" }
        return JSONObject()
            .put("threadId", threadId)
            .put("expectedTurnId", expectedTurnId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", instruction)))
    }

    fun build(threadId: String, text: String, modelId: String, mode: String,
              catalog: List<JSONObject>): JSONObject {
        require(threadId.isNotBlank()) { "缺少会话标识" }
        require(text.isNotBlank()) { "任务内容不能为空" }
        require(mode == "default" || mode == "plan") { "未知工作方式：$mode" }
        val args = JSONObject()
            .put("threadId", threadId)
            .put("input", JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
            ))
        // Default execution inherits the live thread's model and current effort.
        // Only an explicit picker change should override its model.
        if (mode == "default") {
            if (modelId.isNotBlank()) args.put("model", modelId)
            return args
        }
        val selected = modelId.ifBlank {
            catalog.firstOrNull { it.optBoolean("isDefault") }?.optString("id")
                ?: catalog.firstOrNull()?.optString("id").orEmpty()
        }
        require(selected.isNotBlank()) { "计划模式需要先读取可用模型目录" }
        val model = catalog.firstOrNull { it.optString("id") == selected }
        val effort = model?.optString("defaultReasoningEffort")?.takeIf { it.isNotBlank() }
        val settings = JSONObject()
            .put("model", selected)
            .put("reasoning_effort", effort ?: JSONObject.NULL)
            .put("developer_instructions", JSONObject.NULL)
        args.put("collaborationMode",
            JSONObject().put("mode", "plan").put("settings", settings))
        return args
    }
}
