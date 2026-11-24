package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

internal data class MessageBlock(val kind: String, val text: String, val level: Int = 0,
    val label: String = "", val rows: List<List<String>> = emptyList())

/** Parse display structure once; copy and evidence still use the untouched native text. */
internal object MessageMarkdown {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val bullet = Regex("^([-+*]|\\d+[.)])\\s+(.+)$")
    private val fence = Regex("^(\u0060{3,}|~{3,})(.*)$")
    private val delimiter = Regex("^:?-{3,}:?$")
    private val rule = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")
    private val inline = Regex("(\\u0060[^\\u0060]+\\u0060|\\*\\*[^*]+\\*\\*|__[^_]+__|\\*[^*]+\\*|\\[[^\\]\\n]+\\]\\(https?://[^\\s)]+\\))")
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|"))
            .map { it.trim().replace("\\|", "|") }
    private fun tableHeader(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size && lines[index].contains('|') &&
            cells(lines[index]).size > 1 && cells(lines[index + 1]).let { parts ->
                parts.size == cells(lines[index]).size && parts.all { delimiter.matches(it) }
            }

    /** Only commit complete blocks while streaming; blank lines inside code are not boundaries. */
    fun stablePrefixLength(source: String, from: Int = 0): Int {
        var start = from
        var stable = from
        var opening: String? = null
        while (start < source.length) {
            val end = source.indexOf('\n', start)
            if (end < 0) break
            val line = source.substring(start, end).trim()
            val current = opening
            if (current != null) {
                if (line.length >= current.length && line.all { it == current.first() }) opening = null
            } else {
                val marker = fence.matchEntire(line)
                if (marker != null) opening = marker.groupValues[1]
                else if (line.isBlank()) stable = end + 1
            }
            start = end + 1
        }
        return stable
    }

    fun blocks(source: String): List<MessageBlock> {
        val result = mutableListOf<MessageBlock>()
        val lines = source.lines()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                result.add(MessageBlock("paragraph", paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val marker = fence.matchEntire(trimmed)
            val title = heading.matchEntire(trimmed)
            val listItem = bullet.matchEntire(trimmed)
            when {
                marker != null -> {
                    flush()
                    val opening = marker.groupValues[1]
                    val language = marker.groupValues[2].trim().substringBefore(' ')
                    val content = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val end = lines[i].trim()
                        if (end.length >= opening.length && end.all { it == opening.first() }) break
                        content.add(lines[i++])
                    }
                    result.add(MessageBlock("code", content.joinToString("\n"), label = language))
                }
                tableHeader(lines, i) -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows.add(cells(lines[i++]))
                    }
                    result.add(MessageBlock("table", rows.joinToString("\n") { it.joinToString(" | ") }, rows = rows))
                    continue
                }
                trimmed.isBlank() -> flush()
                title != null -> {
                    flush()
                    result.add(MessageBlock("heading", title.groupValues[2], title.groupValues[1].length))
                }
                rule.matches(trimmed) -> { flush(); result.add(MessageBlock("rule", "")) }
                trimmed.startsWith(">") -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quoted.add(lines[i++].trimStart().removePrefix(">").removePrefix(" "))
                    }
                    result.add(MessageBlock("quote", quoted.joinToString("\n")))
                    continue
                }
                listItem != null -> {
                    flush()
                    val prefix = listItem.groupValues[1]
                    result.add(MessageBlock("bullet", listItem.groupValues[2],
                        ((line.length - trimmed.length) / 2).coerceIn(0, 3),
                        if (prefix.first().isDigit()) prefix else "•"))
                }
                else -> paragraph.add(line)
            }
            i++
        }
        flush()
        return result
    }

    fun inlineText(source: String) = buildAnnotatedString {
        var offset = 0
        for (match in inline.findAll(source)) {
            append(source.substring(offset, match.range.first))
            val part = match.value
            when {
                part.startsWith("[") -> {
                    val separator = part.lastIndexOf("](")
                    val url = part.substring(separator + 2, part.length - 1)
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style =
                        SpanStyle(textDecoration = TextDecoration.Underline)))) {
                        append(part.substring(1, separator))
                    }
                }
                part.startsWith("**") || part.startsWith("__") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.drop(2).dropLast(2)) }
                part.startsWith('\u0060') ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.drop(1).dropLast(1)) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.drop(1).dropLast(1)) }
            }
            offset = match.range.last + 1
        }
        append(source.substring(offset))
    }
}

/** Keeps the already verified block boundary while an append-only message grows. */
private class MarkdownBoundary {
    private var prefix = ""
    fun target(source: String, streaming: Boolean): String {
        if (!streaming) return source
        val from = if (source.startsWith(prefix)) prefix.length else 0
        prefix = source.take(MessageMarkdown.stablePrefixLength(source, from))
        return prefix
    }
}

@Composable
internal fun NativeMessageMarkdown(source: String, streaming: Boolean = false) {
    // Keep the already formatted prefix while a long answer is extending. A single conflated
    // collector finishes each background parse; the next delta cannot cancel it repeatedly.
    val boundary = remember { MarkdownBoundary() }
    val target = remember(source, streaming) { boundary.target(source, streaming) }
    val latest by rememberUpdatedState(target)
    var parsed by remember { mutableStateOf<Pair<String, List<MessageBlock>>?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.conflate().collect { value ->
            if (value.length >= 4096) {
                val previous = parsed
                val blocks = withContext(Dispatchers.Default) {
                    if (previous != null && value.startsWith(previous.first) &&
                        MessageMarkdown.stablePrefixLength(previous.first) == previous.first.length) {
                        previous.second + MessageMarkdown.blocks(value.substring(previous.first.length))
                    } else MessageMarkdown.blocks(value)
                }
                parsed = value to blocks
            }
        }
    }
    val ready = if (target.length < 4096) target to remember(target) { MessageMarkdown.blocks(target) }
        else parsed?.takeIf { (prefix, _) -> prefix == target ||
            (target.startsWith(prefix) && MessageMarkdown.stablePrefixLength(prefix) == prefix.length) }
    val prefix = ready?.first.orEmpty()
    val tail = source.substring(prefix.length)
    val unfinished = if (tail.isEmpty()) emptyList() else if (tail.length < 4096) {
        remember(tail) { MessageMarkdown.blocks(tail) }
    } else listOf(MessageBlock("stream-tail", tail))
    val blocks = ready?.second.orEmpty() + unfinished
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { block ->
                when (block.kind) {
                    "code" -> MessageCodeBlock(block)
                    "table" -> MessageTable(block.rows)
                    "rule" -> HorizontalDivider(color = Ink.border)
                    "heading" -> Text(MessageMarkdown.inlineText(block.text), fontWeight = FontWeight.SemiBold,
                        style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                    "bullet" -> Row(Modifier.fillMaxWidth().padding(start = (block.level * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.label, color = Ink.muted)
                        Text(MessageMarkdown.inlineText(block.text), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                    "quote" -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(Modifier.fillMaxHeight().width(3.dp).background(Ink.border, RoundedCornerShape(2.dp)))
                        Text(MessageMarkdown.inlineText(block.text), Modifier.padding(start = 12.dp).weight(1f),
                            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    "stream-tail" -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    else -> Text(MessageMarkdown.inlineText(block.text), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun MessageCodeBlock(block: MessageBlock) {
    val clipboard = LocalClipboardManager.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = Ink.recessed, border = BorderStroke(1.dp, Ink.border)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.label.ifBlank { "代码" }, Modifier.weight(1f), color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
                CodexIconAction(onClick = { clipboard.setText(AnnotatedString(block.text)) },
                    modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.ContentCopy, "复制代码", tint = Ink.muted, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = Ink.border)
            Text(block.text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageTable(rows: List<List<String>>) {
    val count = rows.maxOfOrNull { it.size } ?: return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = Ink.canvas, border = BorderStroke(1.dp, Ink.border)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, row ->
                Row(Modifier.background(if (index == 0) Ink.recessed else Ink.canvas)) {
                    repeat(count) { column ->
                        Text(MessageMarkdown.inlineText(row.getOrElse(column) { "" }),
                            Modifier.width(132.dp).padding(10.dp),
                            color = Ink.parchment, style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                if (index != rows.lastIndex) HorizontalDivider(color = Ink.border)
            }
        }
    }
}
