package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** 任务/资源大文本全部可读、可复制、可检索；分块渲染避免弹层截断。 */
@Composable
fun FullTextReaderDialog(title: String, text: String, onDismiss: () -> Unit) {
    val chunks = remember(text) { text.chunked(FullTextSearchPolicy.CHUNK_SIZE).ifEmpty { listOf("") } }
    var search by remember(text) { mutableStateOf("") }
    var currentMatch by remember(text, search) { mutableIntStateOf(0) }
    val hits = remember(text, search) { FullTextSearchPolicy.find(text, search) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    LaunchedEffect(text, search) {
        hits.firstOrNull()?.let { state.scrollToItem(it / FullTextSearchPolicy.CHUNK_SIZE) }
    }
    fun moveMatch(delta: Int) {
        if (hits.isEmpty()) return
        currentMatch = (currentMatch + delta + hits.size) % hits.size
        val index = hits[currentMatch] / FullTextSearchPolicy.CHUNK_SIZE
        scope.launch {
            if (reducedMotion) state.scrollToItem(index) else state.animateScrollToItem(index)
        }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "关闭全文阅读")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("完整内容 · ${text.length} 字符", color = Ink.muted,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) },
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全文")
                    }
                }
                AtlasSearchField(search, { search = it }, "在全文中查找",
                    modifier = Modifier.testTag("full-text-search"),
                    onClear = { search = "" })
                if (search.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hits.isEmpty()) "没有匹配结果" else
                            "第 ${currentMatch + 1} / ${hits.size} 处" +
                                if (hits.size == FullTextSearchPolicy.MAX_MATCHES) "（最多显示前 2000 处）" else "",
                            color = Ink.muted, modifier = Modifier.weight(1f))
                        IconButton(onClick = { moveMatch(-1) }, enabled = hits.isNotEmpty(),
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "上一个匹配")
                        }
                        IconButton(onClick = { moveMatch(1) }, enabled = hits.isNotEmpty(),
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "下一个匹配")
                        }
                    }
                }
                HorizontalDivider(color = Ink.border)
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 30.dp)) {
                    itemsIndexed(chunks) { index, part ->
                        val marked = buildAnnotatedString {
                            append(part)
                            FullTextSearchPolicy.slices(index, part.length, search.length, hits, currentMatch)
                                .forEach { slice ->
                                    addStyle(SpanStyle(background = Ink.copper.copy(
                                        alpha = if (slice.active) .42f else .15f)), slice.start, slice.end)
                                }
                        }
                        SelectionContainer {
                            Text(marked, fontFamily = FontFamily.Monospace,
                                color = Ink.parchment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
