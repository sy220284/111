package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|"))
            .map { it.trim().replace("\\|", "|") }
    private fun tableHeader(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size && lines[index].contains('|') &&
            cells(lines[index]).isNotEmpty() && cells(lines[index + 1]).let { parts ->
                parts.size == cells(lines[index]).size && parts.all { delimiter.matches(it) }
            }

    /** Only commit complete blocks while streaming; blank lines inside code are not boundaries. */
    fun stablePrefixLength(source: String, from: Int = 0): Int =
        ReplyStablePrefixScanner(from).scan(source)

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
                trimmed == "\$\$" || trimmed == "\\[" -> {
                    val ending = if (trimmed == "\$\$") "\$\$" else "\\]"
                    val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == ending }
                    val sourceMath = end?.let { lines.subList(i + 1, it).joinToString("\n") }
                    val math = sourceMath?.let { RichReplyContent.parse("codex-math", it) }
                    if (math != null && end != null && sourceMath != null) {
                        flush()
                        result.add(MessageBlock("math", sourceMath, rows = math.rows,
                            source = lines.subList(i, end + 1).joinToString("\n")))
                        i = end
                    } else paragraph.add(line)
                }
                ((trimmed.startsWith("\$\$") && trimmed.endsWith("\$\$")) ||
                    (trimmed.startsWith("\\[") && trimmed.endsWith("\\]"))) && trimmed.length > 4 -> {
                    val equation = trimmed.substring(2, trimmed.length - 2)
                    val math = RichReplyContent.parse("codex-math", equation)
                    if (math != null) { flush(); result.add(MessageBlock("math", equation, rows = math.rows, source = line)) }
                    else paragraph.add(line)
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
    internal data class CopyPart(val visible: String, val prefix: String = "", val suffix: String = "",
        val styles: Set<String> = emptySet(), val url: String? = null, val original: String? = null)
    fun copyParts(source: String): List<CopyPart> = ReplyInlineMarkdown.parts(source)

    fun inlineText(source: String, openFile: ((String) -> Unit)? = null) =
        inlineText(copyParts(source), openFile)

    fun inlineText(parts: List<CopyPart>, openFile: ((String) -> Unit)? = null) = buildAnnotatedString {
        for (part in parts) {
            val style = SpanStyle(
                fontWeight = if ("strong" in part.styles) FontWeight.Bold else null,
                fontStyle = if ("emphasis" in part.styles || "math" in part.styles) FontStyle.Italic else null,
                fontFamily = if ("code" in part.styles) FontFamily.Monospace else null,
                textDecoration = if ("strike" in part.styles) TextDecoration.LineThrough else null)
            withStyle(style) {
                val url = part.url
                if (url == null) append(part.visible)
                else if (ReplyLinkPolicy.web(url)) withLink(LinkAnnotation.Url(url, TextLinkStyles(style =
                    SpanStyle(textDecoration = TextDecoration.Underline)))) { append(part.visible) }
                else {
                    val path = ReplyLinkPolicy.file(url)
                    if (path == null || openFile == null) append(part.visible)
                    else withLink(LinkAnnotation.Clickable(url, TextLinkStyles(style =
                        SpanStyle(textDecoration = TextDecoration.Underline)), { openFile(path) })) { append(part.visible) }
                }
            }
        }
    }
}

/** Prepared content is scoped to its block; callbacks remain live without
 * invalidating the token cache on each frame or session callback change. */
internal val LocalPreparedReplyBlock = staticCompositionLocalOf<PreparedReplyBlock?> { null }

@Composable
private fun replyInline(source: String): AnnotatedString {
    val prepared = checkNotNull(LocalPreparedReplyBlock.current)
    val parts = prepared.inline.getValue(source)
    val openFile = LocalReplyFileOpen.current
    val currentOpen by rememberUpdatedState(openFile)
    val canOpen = openFile != null
    return remember(parts, canOpen) {
        MessageMarkdown.inlineText(parts, if (canOpen) ({ path -> currentOpen?.invoke(path) }) else null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativeMessageMarkdown(source: String, streaming: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(source to streaming)
    var document by remember { mutableStateOf<ReplyDocumentEngine.Document?>(null) }
    LaunchedEffect(lifecycle) {
        // Keep one serial worker per message. Conflation drops obsolete render
        // requests, never protocol events or received text.
        val engine = ReplyDocumentEngine()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { latest }.conflate().collect { (text, live) ->
                val prepared = withContext(Dispatchers.Default) { engine.prepare(text, live) }
                document = prepared
            }
        }
    }
    val page = remember(source, document) {
        val ready = document?.takeIf { source.startsWith(it.prefix) }
        val tail = source.substring(ready?.prefixLength ?: 0)
        val blocks = TimelineJoinedList(ready?.blocks.orEmpty(),
            TimelineReplyPages.textChunks(tail).map(PreparedReplyBlock::tail))
        ReplyRenderPage(blocks, 0)
    }
    PreparedMarkdownPage(page)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PreparedMarkdownPage(page: ReplyRenderPage) {
    val blocks = page.blocks
    MessageSelectionContainer(blocks) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var headingIndex = page.firstHeading
            blocks.forEachIndexed { blockIndex, prepared ->
                val block = prepared.block
                key(blockIndex, block.kind) {
                CompositionLocalProvider(LocalPreparedReplyBlock provides prepared) {
                when (block.kind) {
                    "code" -> MessageCodeBlock(block)
                    in RichReplyContent.kinds -> NativeRichReplyBlock(block)
                    "table" -> MessageTable(block)
                    "rule" -> HorizontalDivider(color = Ink.border)
                    "heading" -> {
                        val index = headingIndex++
                        val navigation = LocalReplyHeadingNavigation.current
                        val requester = remember(index) { BringIntoViewRequester() }
                        LaunchedEffect(navigation, index) {
                            if (navigation?.heading == index) {
                                withFrameNanos { }
                                requester.bringIntoView()
                                navigation.onReached()
                            }
                        }
                        Text(replyInline(block.text), Modifier.padding(top = if (blockIndex == 0) 0.dp else 6.dp)
                            .bringIntoViewRequester(requester).semantics { heading() },
                            fontWeight = FontWeight.SemiBold, style = when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            })
                    }
                    "bullet" -> Row(Modifier.fillMaxWidth().padding(start = (block.level * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(block.label, color = Ink.muted)
                        Text(replyInline(block.text), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                    "quote" -> MessageQuote(block)
                    "stream-tail" -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    else -> Text(replyInline(block.text), style = MaterialTheme.typography.bodyLarge)
                }
                }
                }
            }
        }
    }
}

@Composable
private fun MessageCodeBlock(block: MessageBlock) {
    val copy = rememberConversationCopy()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = Ink.recessed) {
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
            val prepared = checkNotNull(LocalPreparedReplyBlock.current)
            if (prepared.codeSlices.isEmpty()) {
                Text(ReplyCodeHighlight.render(block.text, prepared.codeTokens), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("长代码 · 区域内滚动 · 顶部复制完整代码", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                // Bound the inner viewport; never measure the entire code block on the UI thread.
                LazyColumn(Modifier.fillMaxWidth().height(360.dp).horizontalScroll(rememberScrollState())
                    .testTag("message-code-virtual"), contentPadding = PaddingValues(12.dp)) {
                    itemsIndexed(prepared.codeSlices, key = { _, slice -> slice.start }) { _, slice ->
                        Text(ReplyCodeHighlight.render(slice.text, slice.tokens), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageQuote(block: MessageBlock) {
    val copy = rememberConversationCopy()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(Ink.border, RoundedCornerShape(2.dp)))
        Text(replyInline(block.text), Modifier.padding(start = 12.dp).weight(1f),
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
    val count = checkNotNull(LocalPreparedReplyBlock.current).tableColumns
    if (count == 0) return
    var columnPage by remember(block) { mutableIntStateOf(0) }
    val firstColumn = columnPage * 12
    val endColumn = minOf(count, firstColumn + 12)
    val visibleColumns = endColumn - firstColumn
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
        val cellWidth = maxOf(maxWidth / visibleColumns, (82 * codexEffectiveFontScale()).dp)
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
            if (count > 12) DisableSelection {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CodexTextAction(onClick = { columnPage-- }, enabled = columnPage > 0,
                        modifier = Modifier.testTag("message-table-previous-columns")) { Text("上一组列") }
                    Text("${firstColumn + 1}–$endColumn / $count", Modifier.weight(1f), color = Ink.muted,
                        style = MaterialTheme.typography.labelSmall)
                    CodexTextAction(onClick = { columnPage++ }, enabled = endColumn < count,
                        modifier = Modifier.testTag("message-table-next-columns")) { Text("下一组列") }
                }
            }
            HorizontalDivider(color = Ink.border)
            val renderRow: @Composable (Int, List<String>) -> Unit = { index, row ->
                    Row(Modifier.background(if (index == 0) Ink.recessed else Ink.canvas)) {
                        for (column in firstColumn until endColumn) {
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
                                val styled = replyInline(value)
                                Text(AnnotatedString(styled.text, spanStyles = styled.spanStyles), cell,
                                    color = Ink.parchment, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                            } else Text(replyInline(value), cell,
                                color = Ink.parchment, style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    if (index != rows.lastIndex) HorizontalDivider(color = Ink.border)
            }
            val horizontal = rememberScrollState()
            LaunchedEffect(columnPage) { horizontal.scrollTo(0) }
            if (rows.size <= 24) {
                Column(Modifier.horizontalScroll(horizontal)) {
                    rows.forEachIndexed { index, row -> renderRow(index, row) }
                }
            } else {
                Text("长表格 · 区域内滚动 · 支持跨行选取", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                // A fixed cross-axis width preserves the same column alignment across all lazy rows.
                Column(Modifier.horizontalScroll(horizontal).width(cellWidth * visibleColumns)) {
                    renderRow(0, rows.first())
                    LazyColumn(Modifier.fillMaxWidth().height(360.dp).testTag("message-table-virtual")) {
                        items(rows.size - 1, key = { it + 1 }) { index ->
                            renderRow(index + 1, rows[index + 1])
                        }
                    }
                }
            }
        }
    }
    }
}

/** Compose 1.8 SelectionContainer writes its selected visible text through LocalClipboard.
 * Match that text against the same rendered token stream, never against an unrelated answer. */
@Composable
private fun MessageSelectionContainer(blocks: List<PreparedReplyBlock>, content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val context = LocalContext.current
    val latest = rememberUpdatedState(blocks)
    val clipboard = remember(platform, context) {
        object : Clipboard by platform {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                val visible = clipEntry?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString()
                if (visible.isNullOrEmpty()) { platform.setClipEntry(clipEntry); return }
                val snapshot = latest.value
                val selection = withContext(Dispatchers.Default) { MessageSelectionCopy.resolvePrepared(snapshot, visible) }
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
