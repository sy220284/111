package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
fun FullTextReaderDialog(title: String, text: String, onDismiss: () -> Unit) {
    val chunks = remember(text) { text.chunked(FullTextSearchPolicy.CHUNK_SIZE).ifEmpty { listOf("") } }
    var search by remember(text) { mutableStateOf("") }
    var currentMatch by remember(text, search) { mutableIntStateOf(0) }
    val searchHits by key(text, search) {
        if (text.length <= 32768 || search.isBlank()) {
            remember { mutableStateOf<List<Int>?>(FullTextSearchPolicy.find(text, search)) }
        } else {
            produceState<List<Int>?>(null, text, search) {
                value = withContext(Dispatchers.Default) { FullTextSearchPolicy.find(text, search) }
            }
        }
    }
    val hits = searchHits.orEmpty()
    val state = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    val layouts = remember(text) { mutableStateMapOf<Int, TextLayoutResult>() }
    val topInset = with(LocalDensity.current) { 16.dp.roundToPx() }
    LaunchedEffect(text, search, searchHits, currentMatch, topInset) {
        val hit = hits.getOrNull(currentMatch) ?: return@LaunchedEffect
        val index = hit / FullTextSearchPolicy.CHUNK_SIZE
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index } || layouts[index] == null) {
            state.scrollToItem(index)
        }
        val layout = snapshotFlow { layouts[index] }.filterNotNull().first()
        val local = (hit - index * FullTextSearchPolicy.CHUNK_SIZE).coerceIn(0, chunks[index].length)
        val offset = (layout.getLineTop(layout.getLineForOffset(local)).roundToInt() - topInset).coerceAtLeast(0)
        if (reducedMotion || currentMatch == 0) state.scrollToItem(index, offset)
        else state.animateScrollToItem(index, offset)
    }
    fun moveMatch(delta: Int) {
        if (hits.isNotEmpty()) currentMatch = (currentMatch + delta + hits.size) % hits.size
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                    CodexIconAction(onClick = { clipboard.setText(AnnotatedString(text)) },
                        modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ContentCopy, contentDescription = "复制全文")
                    }
                }
                AtlasSearchField(search, { search = it }, "在全文中查找",
                    modifier = Modifier.testTag("full-text-search"),
                    onClear = { search = "" })
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
                HorizontalDivider(color = Ink.border)
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f).testTag("full-text-reader-list"),
                    contentPadding = PaddingValues(bottom = 30.dp)) {
                    itemsIndexed(chunks) { index, part ->
                        DisposableEffect(index) { onDispose { layouts.remove(index) } }
                        val highlight = Ink.copper
                        val marked = remember(part, index, search, hits, currentMatch, highlight) { buildAnnotatedString {
                            append(part)
                            FullTextSearchPolicy.slices(index, part.length, search.length, hits, currentMatch)
                                .forEach { slice ->
                                    addStyle(SpanStyle(background = highlight.copy(
                                        alpha = if (slice.active) .42f else .15f)), slice.start, slice.end)
                                }
                        } }
                        SelectionContainer {
                            Text(marked, fontFamily = FontFamily.Monospace,
                                color = Ink.parchment, style = MaterialTheme.typography.bodyMedium,
                                onTextLayout = { layouts[index] = it },
                                modifier = Modifier.testTag("full-text-chunk-" + index))
                        }
                    }
                }
            }
        }
    }
}
