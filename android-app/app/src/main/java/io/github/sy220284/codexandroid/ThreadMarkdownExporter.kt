package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Export only messages exchanged with the user, excluding raw tool/RPC/diagnostic payloads. */
internal object ThreadMarkdownExporter {
    fun render(history: JSONObject): String {
        val thread = history.optJSONObject("thread")
            ?: throw IllegalArgumentException("无法读取会话正文")
        val turns = thread.optJSONArray("turns") ?: JSONArray()
        val lines = mutableListOf("# Codex 会话记录", "")
        var messages = 0
        for (turnIndex in 0 until turns.length()) {
            val turn = turns.optJSONObject(turnIndex) ?: continue
            val items = turn.optJSONArray("items") ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val heading = when (item.optString("type")) {
                    "userMessage" -> "用户"
                    "agentMessage" -> "Codex"
                    else -> continue
                }
                val direct = item.optString("text").trim()
                val content = item.optJSONArray("content")
                val fragments = if (content == null) emptyList() else
                    (0 until content.length()).mapNotNull { partIndex ->
                        content.optJSONObject(partIndex)?.optString("text")
                            ?.trim()?.takeIf(String::isNotBlank)
                    }
                val body = (direct.takeIf(String::isNotBlank) ?: fragments.joinToString("\n"))
                    .trim()
                if (body.isNotEmpty()) {
                    lines += "## $heading"
                    lines += ""
                    lines += body
                    lines += ""
                    messages++
                }
            }
        }
        require(messages > 0) { "会话暂无可导出的文字消息" }
        return lines.joinToString("\n").trimEnd() + "\n"
    }
}
