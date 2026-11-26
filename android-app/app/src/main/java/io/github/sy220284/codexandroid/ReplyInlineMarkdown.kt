package io.github.sy220284.codexandroid

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Native text and selection use one CommonMark parse, not independent regexes. */
internal object ReplyInlineMarkdown {
    private val parser = Parser.builder().extensions(listOf(StrikethroughExtension.create())).build()
    private val math = Regex("""\\\((.{1,512}?)\\\)""")
    fun parts(source: String): List<MessageMarkdown.CopyPart> {
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<MessageMarkdown.CopyPart>()
        var start = 0
        var scanned = 0
        var insideCode = false
        for (match in math.findAll(source)) {
            // Preserve backtick parity without copying the preceding paragraph.
            while (scanned < match.range.first) {
                if (source[scanned++] == 96.toChar()) insideCode = !insideCode
            }
            if (insideCode) continue
            val value = ReplyMath.parse(match.groupValues[1]) ?: continue
            result += commonmark(source.substring(start, match.range.first))
            result += MessageMarkdown.CopyPart(ReplyMath.linear(value), styles = setOf("math"), original = match.value)
            start = match.range.last + 1
        }
        result += commonmark(source.substring(start))
        return result
    }
    private fun commonmark(source: String): List<MessageMarkdown.CopyPart> {
        if (source.isEmpty()) return emptyList()
        val root = parser.parse(source)
        // The enclosing block parser owns block syntax; do not accidentally remove a
        // literal heading/list marker from a table cell, quote or streaming tail.
        if (root.firstChild !is Paragraph || root.firstChild?.next != null)
            return listOf(MessageMarkdown.CopyPart(source))
        val parts = mutableListOf<MessageMarkdown.CopyPart>()
        fun children(node: Node, prefix: String, suffix: String, styles: Set<String>, url: String?) {
            var child = node.firstChild
            while (child != null) {
                val current = child
                fun append(value: String, pre: String = prefix, post: String = suffix,
                    types: Set<String> = styles, link: String? = url) {
                    if (value.isNotEmpty()) parts += MessageMarkdown.CopyPart(value, pre, post, types, link)
                }
                when (current) {
                    is Text -> append(current.literal)
                    is Code -> {
                        val tick = 96.toChar()
                        val mark = tick.toString().repeat(maxOf(1, Regex(tick.toString() + "+").findAll(current.literal)
                            .maxOfOrNull { it.value.length + 1 } ?: 1))
                        append(current.literal, prefix + mark, mark + suffix, styles + "code")
                    }
                    is StrongEmphasis -> children(current, prefix + "**", "**" + suffix, styles + "strong", url)
                    is Emphasis -> children(current, prefix + "*", "*" + suffix, styles + "emphasis", url)
                    is Strikethrough -> children(current, prefix + "~~", "~~" + suffix, styles + "strike", url)
                    is Link -> {
                        val destination = ReplyLinkPolicy.destination(current.destination)
                        if (destination == null) children(current, prefix, suffix, styles, url)
                        else children(current, prefix + "[", "](" + destination.replace(" ", "%20") + ")" + suffix, styles + "link", destination)
                    }
                    is Image -> {
                        val destination = current.destination.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                        // Images do not perform a hidden network fetch. A real HTTP(S) link is user-opened.
                        append("图片：")
                        children(current, prefix, suffix, styles + if (destination != null) "link" else "image", destination)
                    }
                    is SoftLineBreak, is HardLineBreak -> append("\n")
                    is HtmlInline -> append(current.literal)
                    else -> children(current, prefix, suffix, styles, url)
                }
                child = current.next
            }
        }
        children(root.firstChild, "", "", emptySet(), null)
        // CommonMark removes outer whitespace; preserve it in the visible selection contract.
        val before = source.takeWhile { it == ' ' || it == '\t' || it == '\n' }
        val after = source.takeLastWhile { it == ' ' || it == '\t' || it == '\n' }
        return buildList {
            if (before.isNotEmpty()) add(MessageMarkdown.CopyPart(before))
            addAll(parts)
            if (after.isNotEmpty() && before.length + after.length <= source.length) add(MessageMarkdown.CopyPart(after))
        }
    }
}
