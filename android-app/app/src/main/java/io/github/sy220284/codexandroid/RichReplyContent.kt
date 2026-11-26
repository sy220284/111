package io.github.sy220284.codexandroid

/** Explicit, bounded Markdown display blocks. No remote content, code execution or core protocol changes. */
internal data class RichReplyContent(val kind: String, val rows: List<List<String>>) {
    companion object {
        fun parse(language: String, source: String): RichReplyContent? {
            if (language !in setOf("codex-metrics", "codex-flow") || source.length > 4096) return null
            val lines = source.lines().map(String::trim).filter(String::isNotEmpty)
            if (lines.size !in 1..12) return null
            return when (language) {
                "codex-metrics" -> {
                    val rows = lines.map { it.split('|').map(String::trim) }
                    if (rows.any { it.size !in 2..3 || it.any { value -> value.isBlank() || value.length > 120 } }) null
                    else RichReplyContent("metrics", rows)
                }
                "codex-flow" -> if (lines.any { it.length > 160 }) null
                    else RichReplyContent("flow", lines.map { listOf(it) })
                else -> null
            }
        }
    }
}
