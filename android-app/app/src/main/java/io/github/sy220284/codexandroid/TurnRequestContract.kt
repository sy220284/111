package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON request adapter for the bundled Codex TurnStartParams and CollaborationMode types.
 * CollaborationMode.Settings is serialized by upstream Rust with snake_case fields,
 * while TurnStartParams itself is camelCase. Keep those two layers separate.
 */
object TurnRequestContract {
    /** All send paths share the upstream UserInput schema. Files are real local references. */
    fun input(text: String, localImages: List<String> = emptyList(),
              references: List<ComposerReference> = emptyList()): JSONArray {
        require(text.isNotBlank() || localImages.isNotEmpty() || references.isNotEmpty()) { "任务内容不能为空" }
        require(localImages.size <= LocalImageAttachments.MAX_FILES && localImages.all { it.startsWith("/") }) { "图片附件路径或数量无效" }
        val files = references.filter { it.kind == "file" }
        val prompt = listOf(text.trim(), files.takeIf { it.isNotEmpty() }?.joinToString(
            separator = "\n", prefix = "请读取这些已引用的本地文件：\n") { JSONObject.quote(it.path) }.orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val result = JSONArray()
        if (prompt.isNotBlank()) result.put(JSONObject().put("type", "text").put("text", prompt))
        localImages.forEach { LocalMediaAttachmentPolicy.append(result, it) }
        references.filter { it.kind == "skill" }.forEach {
            result.put(JSONObject().put("type", "skill").put("name", it.name).put("path", it.path))
        }
        return result
    }

    fun steer(threadId: String, expectedTurnId: String, text: String,
              localImages: List<String> = emptyList(), references: List<ComposerReference> = emptyList()): JSONObject {
        require(threadId.isNotBlank() && expectedTurnId.isNotBlank()) { "当前任务尚未取得运行轮次" }
        return JSONObject().put("threadId", threadId).put("expectedTurnId", expectedTurnId)
            .put("input", input(text, localImages, references))
    }

    fun build(threadId: String, text: String, modelId: String, mode: String,
              catalog: List<JSONObject>, localImages: List<String> = emptyList(),
              effort: String = "", references: List<ComposerReference> = emptyList(),
              effectiveConfig: JSONObject? = null): JSONObject {
        require(threadId.isNotBlank()) { "缺少会话标识" }
        require(mode == "default" || mode == "plan") { "未知工作方式：$mode" }
        ComposerModelPolicy.requireEffort(catalog, modelId, effort)
        ComposerModelPolicy.requireMediaInput(catalog, modelId, LocalMediaAttachmentPolicy.modalities(localImages))
        val args = JSONObject().put("threadId", threadId).put("input", input(text, localImages, references))
        // Default execution inherits the live thread's model and current effort.
        // Only an explicit picker change should override its model.
        if (mode == "default") {
            if (modelId.isNotBlank()) args.put("model", modelId)
            if (effort.isNotBlank()) args.put("effort", effort)
            return args
        }
        val configuredModel = effectiveConfig?.optString("model")?.takeIf { it.isNotBlank() && it != "null" }
        val visibleCatalog = catalog.filterNot { it.optBoolean("hidden") }
        val selected = modelId.ifBlank {
            val configured = configuredModel?.let { ComposerModelPolicy.model(visibleCatalog, it) }
            if (configuredModel != null) require(configured != null) { "默认模型不在当前可用目录中，请检查模型设置" }
            configured?.optString("id") ?: visibleCatalog.firstOrNull { it.optBoolean("isDefault") }?.optString("id")
                ?: visibleCatalog.firstOrNull()?.optString("id").orEmpty()
        }
        require(selected.isNotBlank()) { "计划模式需要先读取可用模型目录" }
        val model = requireNotNull(ComposerModelPolicy.model(visibleCatalog, selected)) { "计划模式所选模型已不可用，请重新选择" }
        ComposerModelPolicy.requireMediaInput(visibleCatalog, selected, LocalMediaAttachmentPolicy.modalities(localImages))
        val configuredEffort = effectiveConfig?.optString("plan_mode_reasoning_effort")?.takeIf { it.isNotBlank() && it != "null" }
        val matchingEffort = configuredEffort?.takeIf { it in ComposerModelPolicy.efforts(visibleCatalog, selected) }
        require(effort.isNotBlank() || configuredEffort == null || matchingEffort != null || modelId.isNotBlank()) {
            "计划模式思考深度与默认模型不匹配，请在模型设置中调整"
        }
        val selectedEffort = effort.takeIf { it.isNotBlank() } ?: matchingEffort
            ?: model.optString("defaultReasoningEffort").takeIf { it.isNotBlank() && it != "null" }
        val settings = JSONObject()
            .put("model", selected)
            .put("reasoning_effort", selectedEffort ?: JSONObject.NULL)
            .put("developer_instructions", JSONObject.NULL)
        args.put("collaborationMode",
            JSONObject().put("mode", "plan").put("settings", settings))
        return args
    }
}
