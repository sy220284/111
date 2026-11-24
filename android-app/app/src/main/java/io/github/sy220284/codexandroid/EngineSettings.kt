package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** User-facing settings mapped to the locked upstream ConfigToml contract. */
internal data class EngineSetting(
    val key: String,
    val owner: String,
    val title: String,
    val description: String,
    val type: String,
    val choices: Map<String, String> = emptyMap(),
    val min: Long = 0,
    val max: Long = Long.MAX_VALUE,
    val unit: String = "",
    val scale: Long = 1
) {
    fun parse(input: String, selected: Map<String, String> = choices): Any = when (type) {
        "choice", "reasoning", "model" -> input.also { require(it in selected) { "请选择可用选项" } }
        "boolean" -> when (input) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("请选择开启或关闭")
        }
        "number" -> {
            val number = input.trim().toLongOrNull()
            require(number != null && number in min..max && number <= Long.MAX_VALUE / scale) {
                if (max == Long.MAX_VALUE) "请输入不小于 ${min} 的有效整数" else "请输入 ${min}–${max} 之间的整数"
            }
            number * scale
        }
        "text" -> input
        else -> throw IllegalArgumentException("未知设置类型")
    }

    fun input(value: Any?): String = when {
        value == null || value == JSONObject.NULL -> ""
        type == "number" && value is Number -> if (value.toLong() % scale == 0L) (value.toLong() / scale).toString() else ""
        else -> value.toString()
    }

    fun label(value: Any?, available: Map<String, String> = choices): String {
        if (value == null || value == JSONObject.NULL) return "由引擎决定"
        return when (type) {
            "model" -> available[value.toString()] ?: value.toString()
            "choice", "reasoning" -> available[value.toString()] ?: "自定义选项"
            "boolean" -> if (value == true) "开启" else if (value == false) "关闭" else "自定义"
            "number" -> {
                val number = value as? Number ?: return "自定义"
                if (number.toLong() % scale != 0L) "${number.toLong()} 毫秒" else "${input(value)} ${unit}".trim()
            }
            else -> if (value.toString().isBlank()) "未填写" else "已设置"
        }
    }
}

internal object EngineSettings {
    private val verbosity = linkedMapOf("low" to "简洁", "medium" to "适中", "high" to "详细")
    private val web = linkedMapOf("disabled" to "关闭搜索", "cached" to "缓存搜索",
        "indexed" to "索引搜索", "live" to "实时搜索")
    val reasoningLabels = linkedMapOf("none" to "不额外思考", "minimal" to "极简", "low" to "较快",
        "medium" to "均衡", "high" to "深入", "xhigh" to "深度")
    val all = listOf(
        EngineSetting("model", "models", "默认模型", "选择新会话默认使用的模型；对话中的模型选择仅作为任务运行覆盖。", "model"),
        EngineSetting("model_reasoning_effort", "models", "默认思考深度",
            "复杂任务可选择更深入的思考，通常需要更多时间。选项来自当前默认模型。", "reasoning"),
        EngineSetting("plan_mode_reasoning_effort", "models", "计划模式思考深度",
            "制定任务计划时使用的思考深度，选项来自当前默认模型；对话临时切换到不支持该深度的模型时，沿用该模型默认深度。", "reasoning"),
        EngineSetting("model_verbosity", "models", "回复详细程度",
            "调整支持此功能的模型输出内容的详略。", "choice", verbosity),
        EngineSetting("developer_instructions", "personalization", "全局执行指令",
            "为后续任务补充统一的工作要求。保存会替换原有全局执行指令；清空后保存可移除自定义内容。", "text"),
        EngineSetting("model_context_window", "context", "上下文容量",
            "模型可使用的上下文长度；应与实际模型能力匹配。", "number", min = 1, unit = "词元"),
        EngineSetting("model_auto_compact_token_limit", "context", "自动压缩阈值",
            "对话达到此长度后自动压缩历史内容，建议低于上下文容量。", "number", min = 1, unit = "词元"),
        EngineSetting("model_post_turn_compact_threshold_percent", "context", "回复后压缩比例",
            "回复结束后达到上下文容量的指定比例即压缩；0 表示关闭此项。", "number", max = 100, unit = "%"),
        EngineSetting("tool_output_token_limit", "context", "工具输出保留长度",
            "限制工具输出占用的上下文长度，过小可能丢失细节。", "number", min = 1, unit = "词元"),
        EngineSetting("web_search", "tools", "联网搜索方式",
            "选择搜索资料的方式；实际可用选项受运行策略限制。", "choice", web),
        EngineSetting("thread_unload_delay_secs", "environments", "闲置会话释放时间",
            "会话无活动且无订阅时，等待多久释放运行资源；0 表示立即释放。", "number", unit = "秒"),
        EngineSetting("background_terminal_max_timeout", "environments", "后台终端单次等待上限",
            "后台终端等待一次输出的最长时间，不是整个命令的执行时长。", "number", min = 1, unit = "秒", scale = 1000),
        EngineSetting("hide_agent_reasoning", "appearance", "隐藏推理摘要",
            "减少引擎输出的推理事件；界面是否展示还取决于任务视图。", "boolean")
    )
    fun byKey(key: String) = all.firstOrNull { it.key == key }
    fun forOwner(owner: String) = all.filter { it.owner == owner }
    fun modelChoices(models: List<JSONObject>): Map<String, String> = models.filterNot { it.optBoolean("hidden") }
        .mapNotNull { item ->
            val id = item.optString("model").takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("id").takeIf { it.isNotBlank() && it != "null" }
            id?.let { key -> key to item.optString("displayName").ifBlank { key } }
        }.toMap()
    fun reasoningChoices(config: JSONObject?, models: List<JSONObject>): Map<String, String> {
        val id = config?.optString("model").orEmpty()
        val model = if (id.isBlank() || id == "null") models.firstOrNull { it.optBoolean("isDefault") }
            else models.firstOrNull { it.optString("id") == id || it.optString("model") == id }
        val options = model?.optJSONArray("supportedReasoningEfforts") ?: return emptyMap()
        return (0 until options.length()).mapNotNull { i ->
            val value = options.optJSONObject(i)?.optString("reasoningEffort")?.takeIf { it.isNotBlank() }
            value?.let { it to (reasoningLabels[it] ?: "其他支持选项（$it）") }
        }.toMap()
    }

    fun choices(setting: EngineSetting, config: JSONObject?, models: List<JSONObject>,
        requirements: JSONObject?, requirementsRead: Boolean): Map<String, String> = when {
        setting.type == "model" -> modelChoices(models)
        setting.type == "reasoning" -> reasoningChoices(config, models.filterNot { it.optBoolean("hidden") })
        setting.key == "web_search" -> {
            if (!requirementsRead) emptyMap() else {
                val allowed = requirements?.optJSONArray("allowedWebSearchModes")
                if (allowed == null) setting.choices else setting.choices.filterKeys { key ->
                    (0 until allowed.length()).any { allowed.optString(it) == key }
                }
            }
        }
        else -> setting.choices
    }

    /** Ordered discrete positions, with the actual unset configuration at index zero. */
    fun sliderOptions(setting: EngineSetting, available: Map<String, String>): List<Pair<String?, String>> {
        require(setting.owner == "models")
        val entries = if (setting.type == "reasoning") available.entries.sortedBy { entry ->
            reasoningLabels.keys.indexOf(entry.key).let { if (it < 0) Int.MAX_VALUE else it }
        } else available.entries.toList()
        return listOf(null to "由引擎决定") + entries.map { it.key to it.value }
    }

    fun valueAt(config: JSONObject?, path: String): Any? {
        if (config == null) return null
        if (config.has(path)) return config.opt(path)?.takeUnless { it == JSONObject.NULL }
        var current: Any? = config
        for (part in path.split(".")) current = (current as? JSONObject)?.opt(part)
        return current?.takeUnless { it == JSONObject.NULL }
    }

    fun sameValue(actual: Any?, expected: Any?): Boolean = when {
        actual is JSONObject && expected is JSONObject ->
            actual.length() == expected.length() && expected.keys().asSequence().all {
                actual.has(it) && sameValue(actual.opt(it), expected.opt(it))
            }
        actual is JSONArray && expected is JSONArray ->
            actual.length() == expected.length() && (0 until actual.length()).all {
                sameValue(actual.opt(it), expected.opt(it))
            }
        actual is Number && expected is Number ->
            java.math.BigDecimal(actual.toString()).compareTo(java.math.BigDecimal(expected.toString())) == 0
        else -> actual == expected
    }

    fun writeParams(path: String, value: Any): JSONObject =
        JSONObject().put("keyPath", path.trim()).put("value", value).put("mergeStrategy", "replace")

    fun verify(write: JSONObject, read: JSONObject, path: String, expected: Any) {
        check(write.optString("status") == "ok") { "配置受到更高优先级策略覆盖，请核对生效值" }
        val actual = valueAt(read.getJSONObject("config"), path)
        check(if (expected === JSONObject.NULL) actual == null else sameValue(actual, expected)) {
            "配置已写入，但回读值与修改内容不一致，请核对实际设置"
        }
    }
}
