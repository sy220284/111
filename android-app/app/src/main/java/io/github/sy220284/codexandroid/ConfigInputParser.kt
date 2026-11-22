package io.github.sy220284.codexandroid

import org.json.JSONTokener

/** The UI owns the input type. RPC receives native JSON values, no quoting required by users. */
internal object ConfigInputParser {
    private fun readSingleJsonValue(input: String): Any {
        val parser = JSONTokener(input)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "输入末尾存在多余内容" }
        return value
    }

    fun parse(kind: String, input: String, booleanValue: Boolean): Any {
        return when (kind) {
            "text" -> input.also { require(it.isNotBlank()) { "文本值不能为空" } }
            "boolean" -> booleanValue
            "number" -> {
                require(input.isNotBlank()) { "请输入数字" }
                val value = readSingleJsonValue(input)
                require(value is Number) { "请输入合法数字，例如 30 或 0.5" }
                value
            }
            "json" -> {
                require(input.isNotBlank()) { "请输入 JSON 数据" }
                readSingleJsonValue(input)
            }
            else -> throw IllegalArgumentException("未知设置值类型")
        }
    }
}
