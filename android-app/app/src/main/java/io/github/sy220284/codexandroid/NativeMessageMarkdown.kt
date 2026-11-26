package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
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
    val label: String = "", val rows: List<List<String>> = emptyList(), val source: String = "")

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
            cells(lines[index]).isNotEmpty() && cells(lines[index + 1]).let { parts ->
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
                    val fenceStart = i
                    val opening = marker.groupValues[1]
                    val language = marker.groupValues[2].trim().substringBefore(' ')
                    val content = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val end = lines[i].trim()
                        if (end.length >= opening.length && end.all { it == opening.first() }) break
                        content.add(lines[i++])
                    }
                    val code = content.joinToString("\n")
                    val closed = i < lines.size
                    val rich = if (closed && marker.groupValues[2].trim() == language)
                        RichReplyContent.parse(language, code) else null
                    result.add(if (rich != null) MessageBlock(rich.kind, code, label = language,
                        rows = rich.rows, source = lines.subList(fenceStart, i + 1).joinToString("\n"))
                    else MessageBlock("code", code, label = language))
                }
                trimmed == "\$\$" || trimmed == "\\\\[" -> {
                    val ending = if (trimmed == "\$\$") "\$\$" else "\\\\]"
                    val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == ending }
                    val sourceMath = end?.let { lines.subList(i + 1, it).joinToString(" ") }
                    val math = sourceMath?.let { RichReplyContent.parse("codex-math", it) }
                    if (math != null && end != null) {
                        flush()
                        result.add(MessageBlock("math", sourceMath, rows = math.rows,
                            source = lines.subList(i, end + 1).joinToString("\n")))
                        i = end
                    } else paragraph.add(line)
                }
                tableHeader(lines, i) -> {
                    flush()
                    val start = i
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows.add(cells(lines[i++]))
                    }
                    result.add(MessageBlock("table", rows.joinToString("\n") { it.joinToString(" | ") }, rows = rows,
                        source = lines.subList(start, i).joinToString("\n")))
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
                    val body = listItem.groupValues[2]
                    val task = Regex("^\\[([ xX])\\]\\s+(.+)$").matchEntire(body)
                    result.add(MessageBlock("bullet", task?.groupValues?.get(2) ?: body,
                        ((line.length - trimmed.length) / 2).coerceIn(0, 3),
                        if (task != null) if (task.groupValues[1].isBlank()) "☐" else "☑"
                        else if (prefix.first().isDigit()) prefix else "•"))
                }
                else -> paragraph.add(line)
            }
            i++
        }
        flush()
        return result
    }

    /** Uses exactly the same inline token boundaries as the visible Compose text. */
    internal data class CopyPart(val visible: String, val prefix: String = "", val suffix: String = "")
    fun copyParts(source: String): List<CopyPart> = buildList {
        var offset = 0
        for (match in inline.findAll(source)) {
            if (match.range.first > offset) add(CopyPart(source.substring(offset, match.range.first)))
            val part = match.value
            add(when {
                part.startsWith("[") -> {
                    val separator = part.lastIndexOf("](")
                    CopyPart(part.substring(1, separator), "[", part.substring(separator))
                }
                part.startsWith("**") || part.startsWith("__") ->
                    CopyPart(part.drop(2).dropLast(2), part.take(2), part.takeLast(2))
                else -> CopyPart(part.drop(1).dropLast(1), part.take(1), part.takeLast(1))
            })
            offset = match.range.last + 1
        }
        if (offset < source.length) add(CopyPart(source.substring(offset)))
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
                withContext(Dispatchers.Main.immediate) { parsed = value to blocks }
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
    MessageSelectionContainer(blocks) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { block ->
                when (block.kind) {
                    "code" -> MessageCodeBlock(block)
                    in RichReplyContent.kinds -> NativeRichReplyBlock(block)
                    "table" -> MessageTable(block)
                    "rule" -> HorizontalDivider(color = Ink.border)
                    "heading" -> Text(MessageMarkdown.inlineText(block.text), fontWeight = FontWeight.SemiBold,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        })
                    "bullet" -> Row(Modifier.fillMaxWidth().padding(start = (block.level * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.label, color = Ink.muted)
                        Text(MessageMarkdown.inlineText(block.text), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                    "quote" -> MessageQuote(block)
                    "stream-tail" -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    else -> Text(MessageMarkdown.inlineText(block.text), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun MessageCodeBlock(block: MessageBlock) {
    val copy = rememberConversationCopy()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = Ink.recessed, border = BorderStroke(1.dp, Ink.border)) {
        Column {
            DisableSelection {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(block.label.ifBlank { "代码" }, Modifier.weight(1f), color = Ink.muted,
                        style = MaterialTheme.typography.labelMedium)
                    CodexIconAction(onClick = { copy(block.text, CopyContentFormat.Literal) },
                        modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ContentCopy, "复制代码", tint = Ink.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = Ink.border)
            Text(block.text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageQuote(block: MessageBlock) {
    val copy = rememberConversationCopy()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(Ink.border, RoundedCornerShape(2.dp)))
        Text(MessageMarkdown.inlineText(block.text), Modifier.padding(start = 12.dp).weight(1f),
            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        DisableSelection {
            CodexIconAction(onClick = { copy(block.text, CopyContentFormat.Literal) },
                modifier = Modifier.size(48.dp).testTag("message-quote-copy")) {
                CodexIcon(CodexGlyph.ContentCopy, "复制引用", tint = Ink.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MessageTable(block: MessageBlock) {
    val rows = block.rows
    val count = rows.maxOfOrNull { it.size } ?: return
    val copy = rememberConversationCopy()
    var selecting by remember(block) { mutableStateOf(false) }
    var first by remember(block) { mutableStateOf<Pair<Int, Int>?>(null) }
    var last by remember(block) { mutableStateOf<Pair<Int, Int>?>(null) }
    val start = first
    val end = last
    fun selected(row: Int, column: Int): Boolean {
        val a = start ?: return false
        val b = end ?: a
        return row in minOf(a.first, b.first)..maxOf(a.first, b.first) &&
            column in minOf(a.second, b.second)..maxOf(a.second, b.second)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxOf(maxWidth / count, (82 * codexEffectiveFontScale()).dp)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            color = Ink.canvas, border = BorderStroke(1.dp, Ink.border)) {
        Column {
            DisableSelection {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!selecting) "表格" else if (start == null) "选择起始单元格" else if (end == null)
                    "选择结束单元格" else "已选单元格", Modifier.weight(1f),
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                CodexTextAction(onClick = { selecting = !selecting; first = null; last = null },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("message-table-select")) {
                    Text(if (selecting) "取消" else "选取")
                }
                CodexIconAction(onClick = {
                    if (!selecting) copy(block.source, CopyContentFormat.Markdown)
                    else if (start != null && end != null) {
                        copy(ConversationCopyContent.tableRange(rows, minOf(start.first, end.first),
                            maxOf(start.first, end.first), minOf(start.second, end.second),
                            maxOf(start.second, end.second)), CopyContentFormat.Markdown)
                    }
                }, enabled = !selecting || end != null, modifier = Modifier.size(48.dp).testTag("message-table-copy")) {
                    CodexIcon(CodexGlyph.ContentCopy, if (selecting) "复制所选单元格" else "复制表格",
                        tint = Ink.muted, modifier = Modifier.size(18.dp))
                }
            }
            }
            HorizontalDivider(color = Ink.border)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                rows.forEachIndexed { index, row ->
                    Row(Modifier.background(if (index == 0) Ink.recessed else Ink.canvas)) {
                        repeat(count) { column ->
                            val cell = Modifier.width(cellWidth).then(if (selecting)
                                Modifier.background(if (selected(index, column)) Ink.border else
                                    if (index == 0) Ink.recessed else Ink.canvas)
                                    .clickable {
                                        if (first == null || last != null) { first = index to column; last = null }
                                        else last = index to column
                                    }.testTag("message-table-cell-$index-$column")
                                else Modifier).padding(10.dp)
                            val value = row.getOrElse(column) { "" }
                            if (selecting) DisableSelection {
                                val styled = MessageMarkdown.inlineText(value)
                                Text(AnnotatedString(styled.text, spanStyles = styled.spanStyles), cell,
                                    color = Ink.parchment, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                            } else Text(MessageMarkdown.inlineText(value), cell,
                                color = Ink.parchment, style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    if (index != rows.lastIndex) HorizontalDivider(color = Ink.border)
                }
            }
        }
    }
    }
}

/** Compose 1.8 SelectionContainer writes its selected visible text through LocalClipboard.
 * Match that text against the same rendered token stream, never against an unrelated answer. */
@Composable
private fun MessageSelectionContainer(blocks: List<MessageBlock>, content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val context = LocalContext.current
    val latest = rememberUpdatedState(blocks)
    val clipboard = remember(platform, context) {
        object : Clipboard by platform {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                val visible = clipEntry?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString()
                if (visible.isNullOrEmpty()) { platform.setClipEntry(clipEntry); return }
                val snapshot = latest.value
                val selection = withContext(Dispatchers.Default) { MessageSelectionCopy.resolve(snapshot, visible) }
                // When repeated visible text has incompatible original formatting, retain the actual
                // platform selection rather than guess which source location the user selected.
                if (selection == null) platform.setClipEntry(clipEntry)
                else ConversationClipboard.copy(context, selection.first, selection.second)
            }
        }
    }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        SelectionContainer(content = content)
    }
}
