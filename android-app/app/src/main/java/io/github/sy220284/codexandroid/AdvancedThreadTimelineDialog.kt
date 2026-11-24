package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

/** 实验时间线属于历史详情，提供真正的分页与完整条目，不增加全局页面。 */
@Composable
fun AdvancedThreadTimelineDialog(vm: CodexViewModel, onDismiss: () -> Unit) {
    val thread by vm.activeThread.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var page by remember(thread) { mutableIntStateOf(0) }
    val prefix = "advancedTimeline:" + (thread ?: "none") + ":"
    val currentKey = prefix + page
    val pages = (0..page).mapNotNull { data[prefix + it] }
    val entries = pages.flatMap(::records).distinctBy { it.optLong("position") }
    val cursor = pages.lastOrNull()?.optString("nextCursor").orEmpty()
    LaunchedEffect(thread) {
        if (thread != null) vm.refresh(prefix + "0", "thread/timeline/list",
            JSONObject().put("threadId", thread).put("limit", 50))
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        CodexIcon(CodexGlyph.ArrowBack, contentDescription = "返回回合历史")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("会话时间线", style = MaterialTheme.typography.titleLarge)
                        Text("实验 · 按真实事件顺序浏览",
                            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    }
                }
                CodexNotice("此能力由当前内核按需提供。它包括回合边界、任务条目及实时事件。",
                    NoticeTone.Info)
                if (currentKey in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                errors[currentKey]?.let { ErrorText(it) }
                LazyColumn(Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (thread == null) {
                        item { CodexEmptyState("先选择会话", "时间线属于当前任务记录。") }
                    } else if (entries.isEmpty() && currentKey !in loading) {
                        item {
                            CodexEmptyState("尚无时间线记录",
                                "若内核未启用实验接口，请返回普通回合历史查看已保存内容。")
                        }
                    }
                    itemsIndexed(entries, key = { _, item -> item.optLong("position") }) { index, entry ->
                        MaterialPanel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CodexIcon(CodexGlyph.History, null, tint = Ink.copper)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(PresentationPolicy.timelineKind(entry.optString("type")),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("第 ${index + 1} 项事件",
                                        color = Ink.muted,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            entry.optString("status").takeIf { it.isNotBlank() }?.let {
                                val visibleStatus = PresentationPolicy.timelineStatus(it)
                                if (visibleStatus.isNotEmpty()) StatusPill(visibleStatus,
                                    good = visibleStatus != "失败")
                            }
                            val summary = PresentationPolicy.meaningful(entry.optString("summary"))
                                .ifEmpty { PresentationPolicy.meaningful(entry.optString("text")) }
                            if (summary.isNotEmpty()) {
                                Text(summary, color = Ink.muted,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (cursor.isNotBlank()) {
                        item {
                            OutlinedButton(onClick = {
                                val nextPage = page + 1
                                page = nextPage
                                vm.refresh(prefix + nextPage, "thread/timeline/list",
                                    JSONObject().put("threadId", thread)
                                        .put("cursor", cursor).put("limit", 50))
                            }, modifier = Modifier.fillMaxWidth(),
                                enabled = currentKey !in loading) {
                                Text("继续加载历史事件")
                            }
                        }
                    }
                }
            }
        }
    }
}
