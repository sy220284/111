package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Export user inputs and durable answers; tool and commentary records remain in native history. */
internal object ThreadMarkdownExporter {
    private fun body(item: JSONObject): String {
        val direct = item.optString("text")
        if (direct.isNotEmpty() && !item.isNull("text")) return direct
        val content = item.optJSONArray("content") ?: return ""
        return (0 until content.length()).mapNotNull { content.optJSONObject(it)?.let { part ->
            part.optString("text").takeUnless { part.isNull("text") }
        } }.joinToString("\n")
    }

    fun render(history: JSONObject): String {
        val thread = history.optJSONObject("thread")
            ?: throw IllegalArgumentException("无法读取会话正文")
        val turns = thread.optJSONArray("turns") ?: JSONArray()
        val lines = mutableListOf("# Codex 会话记录", "")
        var messages = 0
        fun emit(heading: String, body: String) {
            if (body.isBlank()) return
            lines += "## $heading"
            lines += ""
            lines += body.trim()
            lines += ""
            messages++
        }
        for (turnIndex in 0 until turns.length()) {
            val turn = turns.optJSONObject(turnIndex) ?: continue
            val raw = turn.optJSONArray("items") ?: continue
            val items = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            val progressive = items.any { it.optString("type") == "agentMessage" && it.optString("phase") == "partial_answer" }
            val pending = StringBuilder()
            fun flush() {
                emit("Codex", pending.toString())
                pending.clear()
            }
            for (item in items) {
                when (item.optString("type")) {
                    "userMessage" -> { flush(); emit("用户", body(item)) }
                    "agentMessage" -> {
                        if (item.optString("phase") == "commentary") continue
                        if (progressive) pending.append(body(item)) else emit("Codex", body(item))
                    }
                }
            }
            flush()
        }
        require(messages > 0) { "会话暂无可导出的文字消息" }
        return lines.joinToString("\n").trimEnd() + "\n"
    }
}
