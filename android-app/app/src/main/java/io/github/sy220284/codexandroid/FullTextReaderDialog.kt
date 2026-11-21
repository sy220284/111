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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** 任务/资源大文本全部可读、可复制、可检索；分块渲染避免弹层截断。 */
@Composable
fun FullTextReaderDialog(title: String, text: String, onDismiss: () -> Unit) {
    val chunks = remember(text) { text.chunked(3200).ifEmpty { listOf("") } }
    var search by remember { mutableStateOf("") }
    var currentMatch by remember { mutableIntStateOf(0) }
    val hits = remember(text, search) {
        if (search.isBlank()) emptyList() else buildList {
            var cursor = 0
            while (cursor < text.length && size < 2000) {
                val found = text.indexOf(search, cursor, ignoreCase = true)
                if (found < 0) break
                add(found)
                cursor = found + search.length.coerceAtLeast(1)
            }
        }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
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
                OutlinedTextField(value = search, onValueChange = {
                    search = it; currentMatch = 0
                }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("在全文中查找") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) })
                if (search.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hits.isEmpty()) "没有匹配结果" else
                            "第 ${currentMatch + 1} / ${hits.size} 处",
                            color = Ink.muted, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            if (hits.isNotEmpty()) {
                                currentMatch = (currentMatch + 1) % hits.size
                                scope.launch { state.animateScrollToItem(hits[currentMatch] / 3200) }
                            }
                        }, enabled = hits.isNotEmpty()) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "下一个匹配")
                        }
                    }
                }
                HorizontalDivider(color = Ink.border)
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 30.dp)) {
                    itemsIndexed(chunks) { _, part ->
                        SelectionContainer {
                            Text(part, fontFamily = FontFamily.Monospace,
                                color = Ink.parchment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
