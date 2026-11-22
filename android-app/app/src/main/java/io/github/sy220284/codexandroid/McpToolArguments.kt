package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validates MCP tool parameters before presenting the irreversible call consent.
 * Nested JSON is edited in the advanced form; common schema constraints are
 * checked locally, and the server remains authoritative for nested constraints.
 */
internal object McpToolArguments {
    fun problem(schema: JSONObject?, arguments: JSONObject?): String? {
        if (schema == null) return "服务没有提供工具参数结构，请刷新 MCP 服务列表"
        if (schema.optString("type").let { it.isNotBlank() && it != "object" }) {
            return "该工具的参数结构不是对象，请检查服务端工具定义"
        }
        if (arguments == null) return "工具参数 JSON 格式错误"
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (index in 0 until required.length()) {
            val name = required.optString(index)
            if (name.isBlank()) return "服务端必填字段定义无效"
            if (!arguments.has(name) || arguments.isNull(name)) {
                return "请填写必填参数：$name"
            }
        }
        val keys = arguments.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val def = properties.optJSONObject(name)
            if (def == null) {
                if (schema.opt("additionalProperties") == false) {
                    return "工具不接受额外参数：$name"
                }
                continue
            }
            if (arguments.isNull(name)) continue
            val value = arguments.opt(name)
            val type = def.optString("type")
            val validType = when (type) {
                "string" -> value is String
                "integer" -> value is Number && value.toDouble().isFinite() &&
                    value.toDouble() % 1.0 == 0.0
                "number" -> value is Number && value.toDouble().isFinite()
                "boolean" -> value is Boolean
                "array" -> value is JSONArray
                "object" -> value is JSONObject
                else -> true
            }
            if (!validType) return "参数 $name 的类型与工具要求不一致（$type）"
            val choices = def.optJSONArray("enum")
            if (choices != null && (0 until choices.length()).none {
                    choices.opt(it) == value ||
                        choices.opt(it)?.toString() == value?.toString()
                }) return "参数 $name 不在允许的选项中"
        }
        return null
    }
}
