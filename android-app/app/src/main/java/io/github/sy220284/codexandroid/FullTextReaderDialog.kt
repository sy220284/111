package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 任务/资源大文本全部可读、可复制、可检索；分块渲染避免弹层截断。 */
@Composable
internal fun FullTextReaderDialog(title: String, text: String,
    copyFormat: CopyContentFormat = CopyContentFormat.Literal, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val chunks by loadCodexUiState<List<ReaderTextIndex.Chunk>?>(null, text,
            dispatcher = Dispatchers.Default) { ReaderTextIndex.chunks(text) }
        var selecting by remember(text) { mutableStateOf(false) }
        var firstOffset by remember(text) { mutableStateOf<Int?>(null) }
        var lastOffset by remember(text) { mutableStateOf<Int?>(null) }
        val selected = firstOffset?.let { first -> lastOffset?.let { ReaderTextIndex.range(text, first, it) } }
        fun select(offset: Int) {
            val normalized = ReaderTextIndex.boundary(text, offset)
            if (firstOffset == null || lastOffset != null) { firstOffset = normalized; lastOffset = null }
            else lastOffset = normalized
        }
        var search by remember(text) { mutableStateOf("") }
        var currentMatch by remember(text, search) { mutableIntStateOf(0) }
        val query = search
        val searchHits: List<Int>? = if (text.length <= 32768 || query.isBlank()) {
            remember(text, query) { FullTextSearchPolicy.find(text, query) }
        } else {
            val requestedQuery = query
            val result by loadCodexUiState<Triple<String, String, List<Int>>?>(null, text, requestedQuery,
                dispatcher = Dispatchers.Default) {
                Triple(text, requestedQuery, FullTextSearchPolicy.find(text, requestedQuery))
            }
            result?.takeIf { it.first == text && it.second == query }?.third
        }
        val hits = searchHits.orEmpty()
        val state = rememberLazyListState()
        val copy = rememberConversationCopy()
        val reducedMotion by UiPreferences.reducedMotion.collectAsState()
        val layouts = remember(text) { mutableStateMapOf<Int, TextLayoutResult>() }
        val topInset = with(LocalDensity.current) { 16.dp.roundToPx() }
        LaunchedEffect(text, search, searchHits, currentMatch, topInset, chunks) {
            val hit = hits.getOrNull(currentMatch) ?: return@LaunchedEffect
            val parts = chunks ?: return@LaunchedEffect
            val index = ReaderTextIndex.chunkIndex(parts, hit)
            if (state.layoutInfo.visibleItemsInfo.none { it.index == index } || layouts[index] == null) {
                state.scrollToItem(index)
            }
            val layout = snapshotFlow { layouts[index] }.filterNotNull().first()
            val local = (hit - parts[index].start).coerceIn(0, parts[index].text.length)
            val offset = (layout.getLineTop(layout.getLineForOffset(local)).roundToInt() - topInset).coerceAtLeast(0)
            if (reducedMotion || currentMatch == 0) state.scrollToItem(index, offset)
            else state.animateScrollToItem(index, offset)
        }
        fun moveMatch(delta: Int) {
            if (hits.isNotEmpty()) currentMatch = (currentMatch + delta + hits.size) % hits.size
        }
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize().testTag("full-text-reader")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CodexIconAction(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ArrowBack, contentDescription = "关闭全文阅读")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("完整内容 · ${text.length} 字符", color = Ink.muted,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    CodexIconAction(onClick = { copy(text, copyFormat) },
                        modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ContentCopy, contentDescription = "复制全文")
                    }
                }
                ClipboardQueryAction("使用剪贴板查找正文", search, { search = it }, { search = "" }, tag = "full-text-clipboard-query")
                if (search.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searchHits == null) "正在查找…" else if (hits.isEmpty()) "没有匹配结果" else
                            "第 ${currentMatch + 1} / ${hits.size} 处" +
                                if (hits.size == FullTextSearchPolicy.MAX_MATCHES) "（最多显示前 2000 处）" else "",
                            color = Ink.muted, modifier = Modifier.weight(1f))
                        CodexIconAction(onClick = { moveMatch(-1) }, enabled = hits.isNotEmpty(),
                            modifier = Modifier.size(48.dp)) {
                            CodexIcon(CodexGlyph.ArrowUpward, contentDescription = "上一个匹配")
                        }
                        CodexIconAction(onClick = { moveMatch(1) }, enabled = hits.isNotEmpty(),
                            modifier = Modifier.size(48.dp)) {
                            CodexIcon(CodexGlyph.ArrowDownward, contentDescription = "下一个匹配")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CodexTextAction(onClick = {
                        selecting = !selecting; firstOffset = null; lastOffset = null
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("full-text-range-toggle")) {
                        Text(if (selecting) "取消跨页选取" else "跨页选取")
                    }
                    if (selecting) {
                        Text(when {
                            firstOffset == null -> "点击正文确定起点"
                            lastOffset == null -> "滚动后点击终点"
                            else -> "已选 ${selected?.let { it.end - it.start } ?: 0} 字符"
                        }, modifier = Modifier.weight(1f), color = Ink.muted,
                            style = MaterialTheme.typography.labelSmall)
                        CodexIconAction(onClick = { selected?.let { copy(it.copy(text), copyFormat) } },
                            enabled = selected?.let { it.end > it.start && text.substring(it.start, it.end).isNotBlank() } == true,
                            modifier = Modifier.size(48.dp).testTag("full-text-range-copy")) {
                            CodexIcon(CodexGlyph.ContentCopy, "复制跨页选区")
                        }
                    }
                }
                if (chunks == null) AtlasOperationProgress("正在准备全文")
                HorizontalDivider(color = Ink.border)
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f).testTag("full-text-reader-list"),
                    contentPadding = PaddingValues(bottom = 30.dp)) {
                    itemsIndexed(chunks.orEmpty(), key = { _, chunk -> chunk.start }) { index, chunk ->
                        val part = chunk.text
                        DisposableEffect(index) { onDispose { layouts.remove(index) } }
                        val highlight = Ink.copper
                        val marked = remember(chunk, search, hits, currentMatch, highlight, selected) { buildAnnotatedString {
                            append(part)
                            FullTextSearchPolicy.slicesAt(chunk.start, part.length, search.length, hits, currentMatch)
                                .forEach { slice ->
                                    addStyle(SpanStyle(background = highlight.copy(
                                        alpha = if (slice.active) .42f else .15f)), slice.start, slice.end)
                                }
                            selected?.intersect(chunk)?.let { range ->
                                addStyle(SpanStyle(background = highlight.copy(alpha = .48f)),
                                    range.first, range.last + 1)
                            }
                        } }
                        val content: @Composable () -> Unit = {
                            Text(marked, fontFamily = FontFamily.Monospace,
                                color = Ink.parchment, style = MaterialTheme.typography.bodyMedium,
                                onTextLayout = { layouts[index] = it },
                                modifier = Modifier.testTag("full-text-chunk-" + index).then(
                                    if (selecting) Modifier.pointerInput(text, chunk.start) {
                                        detectTapGestures { point ->
                                            layouts[index]?.let { layout -> select(chunk.start + layout.getOffsetForPosition(point)) }
                                        }
                                    } else Modifier))
                        }
                        if (selecting) {
                            // Boundary buttons offer a keyboard/accessibility alternative
                            // and precise taps can select an offset inside a chunk.
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                CodexTextAction(onClick = { select(chunk.start) },
                                    modifier = Modifier.testTag("full-text-range-start-$index")) { Text("选此段开头") }
                                CodexTextAction(onClick = { select(chunk.end) },
                                    modifier = Modifier.testTag("full-text-range-end-$index")) { Text("选此段末尾") }
                            }
                            content()
                        } else SelectionContainer(content = content)
                    }
                }
            }
        }
    }
}
