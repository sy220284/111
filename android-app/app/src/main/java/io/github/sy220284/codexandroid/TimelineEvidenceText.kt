package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Explicit expert reader: preserve exact command, output and paths, never display raw reasoning. */
internal object TimelineEvidenceText {
    fun render(item: TimelineItem): String {
        if (item.evidence.isBlank()) return item.text
        val raw = try { JSONObject(item.evidence) } catch (_: org.json.JSONException) { return item.evidence }
        return buildString {
            for ((key, title) in listOf("command" to "执行命令", "cwd" to "工作目录",
                "exitCode" to "退出码", "aggregatedOutput" to "执行输出", "output" to "输出",
                "stderr" to "错误输出", "progress" to "工具进度")) {
                if (!raw.isNull(key)) append(title).append('\n').append(raw.get(key)).append("\n\n")
            }
            if (item.kind == "reasoning") raw.remove("content")
            append("原始协议记录\n").append(raw.toString(2))
        }
    }
}
