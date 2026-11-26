package io.github.sy220284.codexandroid

/** Recover the selected visible characters with only their own Markdown wrappers.
 * SelectionContainer concatenates the selected child text, without inserting separators. */
internal object MessageSelectionCopy {
    private data class Segment(val parts: List<MessageMarkdown.CopyPart>, val kind: String, val marker: String = "") {
        val visible = parts.joinToString("") { it.visible }
        fun slice(from: Int, to: Int): Pair<String, Boolean> {
            if (kind == "code" || kind == "stream-tail") return visible.substring(from, to) to false
            var position = 0
            var styled = false
            val text = buildString {
                for (part in parts) {
                    val end = position + part.visible.length
                    val a = maxOf(from, position)
                    val b = minOf(to, end)
                    if (a < b) {
                        val value = part.visible.substring(a - position, b - position)
                        if (part.prefix.isNotEmpty()) {
                            styled = true
                            append(part.prefix).append(value).append(part.suffix)
                        } else append(value)
                    }
                    position = end
                }
            }
            return if (marker.isNotEmpty() && from == 0 && to == visible.length)
                (marker + text) to true else text to styled
        }
    }

    fun resolve(blocks: List<MessageBlock>, selected: String): Pair<String, CopyContentFormat>? {
        if (selected.isBlank()) return null
        val segments = blocks.flatMap { block ->
            when (block.kind) {
                "rule", "metrics", "flow" -> emptyList()
                "code", "stream-tail" -> listOf(Segment(listOf(MessageMarkdown.CopyPart(block.text)), block.kind))
                "table" -> block.rows.flatten().map { Segment(MessageMarkdown.copyParts(it), "table") }
                "heading" -> listOf(Segment(MessageMarkdown.copyParts(block.text), block.kind,
                    "#".repeat(block.level.coerceIn(1, 6)) + " "))
                "bullet" -> listOf(Segment(listOf(MessageMarkdown.CopyPart(block.label)) +
                    MessageMarkdown.copyParts(block.text), block.kind))
                else -> listOf(Segment(MessageMarkdown.copyParts(block.text), block.kind))
            }
        }.filter { it.visible.isNotEmpty() }
        val source = segments.joinToString("") { it.visible }
        fun at(start: Int): Pair<String, CopyContentFormat> {
            var position = 0
            var styled = false
            val values = mutableListOf<Pair<Segment, String>>()
            for (segment in segments) {
                val end = position + segment.visible.length
                val a = maxOf(start, position)
                val b = minOf(start + selected.length, end)
                if (a < b) {
                    val (value, rich) = segment.slice(a - position, b - position)
                    val restored = if (segment.kind == "bullet" && a == position &&
                        value.startsWith("•") && value.length > 1) "- " + value.drop(1)
                    else if (segment.kind == "bullet" && a == position && segment.parts.firstOrNull()?.visible?.let {
                            it.firstOrNull()?.isDigit() == true && value.startsWith(it) && value.length > it.length
                        } == true) {
                        val label = segment.parts.first().visible
                        label + " " + value.drop(label.length)
                    } else value
                    values += segment to restored
                    styled = styled || rich || restored != value
                }
                position = end
                if (position >= start + selected.length) break
            }
            val mixed = values.size > 1
            if (mixed && values.any { it.first.kind == "code" || it.first.kind == "quote" }) styled = true
            fun decorate(segment: Segment, value: String): String {
                if (!mixed) return value
                if (segment.kind == "quote") return value.lines().joinToString("\n") { "> $it" }
                if (segment.kind == "code") {
                    val tick = 96.toChar()
                    val fence = tick.toString().repeat(maxOf(3, Regex(tick.toString() + "+").findAll(value)
                        .maxOfOrNull { it.value.length + 1 } ?: 3))
                    return "$fence\n$value\n$fence"
                }
                return value
            }
            val rendered = buildString {
                for ((index, entry) in values.withIndex()) {
                    if (index > 0) append(if (entry.first.kind == "bullet" && values[index - 1].first.kind == "bullet")
                        "\n" else "\n\n")
                    append(decorate(entry.first, entry.second))
                }
            }
            return rendered to if (styled) CopyContentFormat.Markdown else CopyContentFormat.Literal
        }
        val start = source.indexOf(selected)
        if (start < 0) return null
        val result = at(start)
        var next = source.indexOf(selected, start + 1)
        // The same visible selection can occur in differently formatted source. Avoid
        // leaking unrelated link destinations or style markers from another occurrence.
        var matches = 0
        while (next >= 0) {
            if (++matches > 64 || at(next) != result) return null
            next = source.indexOf(selected, next + 1)
        }
        return result
    }
}
