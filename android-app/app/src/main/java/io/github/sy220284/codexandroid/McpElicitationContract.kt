package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/**
 * Translate the official v2/mcp.rs elicitation schema into a structured result.
 * Permissions are granted only for a form the user explicitly completed.
 */
object McpElicitationContract {
    private val supportedForms = setOf("form", "openai/form", "openaiForm")

    fun respond(params: JSONObject, decision: String, inputs: Map<String, String>): JSONObject {
        require(decision in setOf("accept", "decline", "cancel")) { "无效的 MCP 表单决策" }
        if (decision != "accept") {
            return JSONObject().put("action", decision).put("content", JSONObject.NULL)
        }
        val kind = params.optString("type")
        if (kind == "url") {
            val uri = params.optString("url")
            require(uri.startsWith("https://") || uri.startsWith("http://127.0.0.1:")) {
                "授权页面须使用 HTTPS 或本机回调地址"
            }
            return JSONObject().put("action", "accept").put("content", JSONObject.NULL)
        }
        require(kind in supportedForms) { "该 MCP 请求类型尚未具备验证后的提交路径：" + kind }
        val schema = params.optJSONObject("requestedSchema")
            ?: throw IllegalArgumentException("MCP 服务未提供表单结构")
        require(schema.optString("type") == "object") { "MCP 表单根结构必须为 object" }
        val properties = schema.optJSONObject("properties")
            ?: throw IllegalArgumentException("MCP 表单缺少字段定义")
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val key = required.getString(i)
            require(inputs.containsKey(key)) { "必填字段尚未填写：" + key }
        }
        for (key in inputs.keys) {
            require(properties.has(key)) { "表单不包含此字段：" + key }
        }
        val content = JSONObject()
        for ((key, raw) in inputs) {
            val field = properties.getJSONObject(key)
            val kindName = field.optString("type")
            val value: Any = when (kindName) {
                "string" -> {
                    val min = field.optInt("minLength", 0)
                    val max = field.optInt("maxLength", Int.MAX_VALUE)
                    require(raw.length in min..max) { "字段 $key 的长度不符合要求" }
                    val choices = field.optJSONArray("enum")
                    if (choices != null) {
                        require((0 until choices.length()).any { choices.optString(it) == raw }) {
                            "$key 必须从服务端提供的选项中选择"
                        }
                    }
                    raw
                }
                "number", "integer" -> {
                    val numeric = raw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("字段 $key 须填写数字")
                    require(numeric.isFinite()) { "字段 $key 的数值不可用" }
                    if (kindName == "integer") require(numeric % 1.0 == 0.0) { "字段 $key 须填写整数" }
                    val lower = field.opt("minimum")
                    val upper = field.opt("maximum")
                    if (lower is Number) require(numeric >= lower.toDouble()) { "字段 $key 低于允许值" }
                    if (upper is Number) require(numeric <= upper.toDouble()) { "字段 $key 高于允许值" }
                    if (kindName == "integer") {
                        raw.toLongOrNull() ?: throw IllegalArgumentException("字段 $key 超出整数范围")
                    } else numeric
                }
                "boolean" -> when (raw) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("字段 $key 须为 true 或 false")
                }
                "array" -> {
                    val array = JSONArray(raw)
                    val items = field.optJSONObject("items")?.optJSONArray("enum")
                    if (items != null) {
                        for (i in 0 until array.length()) {
                            require((0 until items.length()).any { items.optString(it) == array.getString(i) }) {
                                "字段 $key 包含未列出的选项"
                            }
                        }
                    }
                    array
                }
                else -> throw IllegalArgumentException("尚未实现此表单类型：$kindName")
            }
            content.put(key, value)
        }
        return JSONObject().put("action", "accept").put("content", content)
    }
}
