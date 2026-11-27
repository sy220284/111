package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Validated user-facing subset of the locked desktop safety_recourse metadata contract. */
internal object SafetyRecourseContract {
    private val topics = mapOf(
        "biological_agent_access_and_safeguards" to "生物材料的获取流程和安全控制",
        "biological_agent_growth_or_production" to "相关科学背景和安全注意事项",
        "risky_biological_experimentation_or_modification" to "研究目标和安全注意事项",
        "harmful_biological_exposure_or_release" to "风险、预防与应对",
        "risky_biological_activities" to "生物研究和安全注意事项",
        "unauthorized_account_or_system_access" to "账号安全和恢复",
        "high_risk_cybersecurity_tools" to "系统防护和事件恢复",
        "sensitive_data_or_private_communications" to "数据保护和隐私保障",
        "software_vulnerabilities_or_security_weaknesses" to "安全评估和漏洞修复",
        "risky_cybersecurity_activities" to "网络安全基础和防御实践"
    )
    private val bioTopics = topics.keys.take(5).toSet()
    private fun fields(raw: JSONObject, vararg names: String) = raw.keys().asSequence().toSet() == names.toSet()
    private fun valid(raw: String, limit: Int) = raw.isNotBlank() && raw.codePointCount(0, raw.length) <= limit
    fun parse(metadata: JSONObject): JSONObject? {
        if (metadata.isNull("safety_recourse")) {
            val block = metadata.optJSONObject("safety_block") ?: return null
            if (!fields(block, "schema_version", "domain", "status") ||
                block.opt("schema_version") !is Number || block.optDouble("schema_version") != 1.0 ||
                block.optString("domain") !in setOf("bio", "cyber") || block.optString("status") != "loading") return null
            return JSONObject().put("domain", block.getString("domain")).put("status", "loading")
        }
        val value = metadata.optJSONObject("safety_recourse") ?: return null
        if (!fields(value, "schema_version", "domain", "source", "source_message", "topic", "suggestions") ||
            value.opt("schema_version") !is Number || value.optDouble("schema_version") != 1.0) return null
        val domain = value.optString("domain")
        val topic = value.optString("topic")
        if (domain !in setOf("bio", "cyber") || topic !in topics || (topic in bioTopics) != (domain == "bio")) return null
        val message = value.optJSONObject("source_message") ?: return null
        if (!fields(message, "id", "kind", "role") || !valid(message.optString("id"), 256)) return null
        val source = Triple(value.optString("source"), message.optString("kind"), message.optString("role"))
        val allowed = setOf(Triple("user_request", "ordinary_message", "user"),
            Triple("assistant_response", "ordinary_message", "assistant"),
            Triple("file_content", "file_tool_call", "assistant"), Triple("file_content", "file_tool_result", "tool"),
            Triple("web_search_result", "web_tool_call", "assistant"), Triple("web_search_result", "web_tool_result", "tool"),
            Triple("other_tool", "other_tool_call", "assistant"), Triple("other_tool", "other_tool_result", "tool"))
        if (source !in allowed) return null
        val rows = value.optJSONArray("suggestions") ?: return null
        if (rows.length() != 3) return null
        val options = (0 until rows.length()).map { rows.optJSONObject(it) ?: return null }
        if (options.any { !fields(it, "label", "prompt_text") ||
                it.opt("label") !is String || it.opt("prompt_text") !is String ||
                !valid(it.getString("label").trim(), 80) || !valid(it.getString("prompt_text").trim(), 2000) }) return null
        val labels = options.map { it.getString("label").trim().lowercase(java.util.Locale.ROOT) }
        val prompts = options.map { it.getString("prompt_text").trim() }
        if (prompts.distinct().size != prompts.size) return null
        val suggestions = JSONArray()
        for ((index, option) in options.withIndex()) {
            val label = option.getString("label").trim()
            val prompt = prompts[index]
            // No implicit commands, tool mentions, structured question replies or entitlement changes.
            if (label.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } ||
                prompt.toByteArray(Charsets.UTF_8).size > 1000 ||
                prompt.startsWith("!") || prompt.startsWith("/") || prompt.startsWith("{") ||
                prompt.contains('@') || prompt.contains('$') || prompt.any(Char::isISOControl) ||
                label.equals("Enable Daybreak", true) || labels.count { it == labels[index] } != 1) continue
            suggestions.put(JSONObject().put("label", label).put("prompt", prompt))
        }
        return JSONObject().put("domain", domain).put("status", "ready")
            .put("explanation", "当前任务受到安全限制。可以继续讨论" + topics.getValue(topic) + "。")
            .put("suggestions", suggestions)
    }
    private fun bioMessage(message: String): Boolean = message.startsWith("Invalid prompt: we've limited access to this content for safety reasons.") ||
        message.startsWith("This content was flagged for possible biological risk.") ||
        message.startsWith("This request has been flagged for possible biological risk.")
    fun errorDomain(error: JSONObject?): String? {
        if (error == null) return null
        if (error.optString("codexErrorInfo") == "cyberPolicy") return "cyber"
        val message = error.optString("message")
        if (bioMessage(message)) return "bio"
        val nested = try { JSONObject(message).optJSONObject("error") } catch (_: org.json.JSONException) { null }
        return if (nested?.optString("code") == "bio_policy" || bioMessage(nested?.optString("message").orEmpty())) "bio" else null
    }
}
