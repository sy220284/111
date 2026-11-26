package io.github.sy220284.codexandroid

/** Bounded, declarative components; all data is validated before entering Compose. */
internal data class RichReplyContent(val kind: String, val rows: List<List<String>>) {
    companion object {
        val kinds = setOf("metrics", "flow", "bar", "line", "pie", "graph", "checklist", "math")
        private val chartKinds = setOf("bar", "line", "pie")
        fun parse(language: String, source: String): RichReplyContent? {
            val kind = language.removePrefix("codex-")
            if (language != "codex-$kind" || kind !in kinds || source.length > 4096) return null
            val lines = source.lines().map(String::trim).filter(String::isNotEmpty)
            val limit = if (kind in chartKinds || kind == "graph") 24 else 12
            if (lines.size !in 1..limit) return null
            val fields = lines.map { it.split('|').map(String::trim) }
            fun valid(rows: List<List<String>>, range: IntRange) = rows.all { row ->
                row.size in range && row.all { it.isNotBlank() && it.length <= 160 }
            }
            val rows = when (kind) {
                "metrics" -> fields.takeIf { valid(it, 2..3) && it.flatten().all { value -> value.length <= 120 } }
                "flow" -> lines.map { listOf(it) }.takeIf { valid(it, 1..1) }
                "bar", "line", "pie" -> fields.takeIf {
                    valid(it, 2..2) && it.map { row -> row[0] }.distinct().size == it.size &&
                        it.all { row -> row[1].toDoubleOrNull()?.let { n ->
                            n.isFinite() && kotlin.math.abs(n) <= 1e15 && (kind != "pie" || n >= 0)
                        } == true } && (kind != "pie" || it.any { row -> row[1].toDouble() > 0 }) &&
                        (kind != "line" || it.size >= 2)
                }
                "graph" -> fields.takeIf { valid(it, 3..3) && it.distinct().size == it.size &&
                    it.all { row -> row[0] != row[2] } && it.flatMap { row -> listOf(row[0], row[2]) }.distinct().size <= 16 }
                "checklist" -> fields.takeIf { valid(it, 2..2) && it.all { row -> row[0] in setOf("done", "todo", "doing") } }
                "math" -> lines.map { listOf(it) }.takeIf { valid(it, 1..1) && it.all { row -> ReplyMath.parse(row[0]) != null } }
                else -> null
            } ?: return null
            return RichReplyContent(kind, rows)
        }
    }
}
