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
              catalog: List<JSONObject>, localImages: List<String> = emptyList(),
              effectiveConfig: JSONObject? = null): JSONObject {
        require(threadId.isNotBlank()) { "缺少会话标识" }
        require(text.isNotBlank() || localImages.isNotEmpty()) { "任务内容和图片不能同时为空" }
        require(localImages.size <= LocalImageAttachments.MAX_FILES) { "图片数量超过上限" }
        require(localImages.all { it.startsWith("/") && it.isNotBlank() }) {
            "图片附件必须使用本地绝对路径"
        }
        require(mode == "default" || mode == "plan") { "未知工作方式：$mode" }
        val input = JSONArray()
        if (text.isNotBlank()) input.put(JSONObject().put("type", "text").put("text", text))
        localImages.forEach { path ->
            input.put(JSONObject().put("type", "localImage").put("path", path))
        }
        val args = JSONObject()
            .put("threadId", threadId)
            .put("input", input)
        // Default execution inherits the live thread's model and current effort.
        // Only an explicit picker change should override its model.
        if (mode == "default") {
            if (modelId.isNotBlank()) args.put("model", modelId)
            return args
        }
        val configuredModel = effectiveConfig?.optString("model")?.takeIf { it.isNotBlank() && it != "null" }
        val selected = modelId.ifBlank {
            val availableConfigured = catalog.firstOrNull { configuredModel != null &&
                (it.optString("id") == configuredModel || it.optString("model") == configuredModel) }
            if (configuredModel != null) require(availableConfigured != null) { "默认模型不在当前可用目录中，请检查模型设置" }
            availableConfigured?.optString("id") ?: catalog.firstOrNull { it.optBoolean("isDefault") }?.optString("id")
                ?: catalog.firstOrNull()?.optString("id").orEmpty()
        }
        require(selected.isNotBlank()) { "计划模式需要先读取可用模型目录" }
        val model = catalog.firstOrNull { it.optString("id") == selected }
        val configuredEffort = effectiveConfig?.optString("plan_mode_reasoning_effort")?.takeIf { it.isNotBlank() && it != "null" }
        val supported = model?.optJSONArray("supportedReasoningEfforts")
        val effort = configuredEffort?.takeIf { selectedEffort ->
            supported != null && (0 until supported.length()).any { supported.optJSONObject(it)?.optString("reasoningEffort") == selectedEffort }
        } ?: model?.optString("defaultReasoningEffort")?.takeIf { it.isNotBlank() }
        val settings = JSONObject()
            .put("model", selected)
            .put("reasoning_effort", effort ?: JSONObject.NULL)
            .put("developer_instructions", JSONObject.NULL)
        args.put("collaborationMode",
            JSONObject().put("mode", "plan").put("settings", settings))
        return args
    }
}
