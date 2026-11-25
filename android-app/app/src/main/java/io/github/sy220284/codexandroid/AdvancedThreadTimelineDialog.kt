package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
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
    val cursor = data[currentKey]?.optString("nextCursor").orEmpty()
    var category by rememberSaveable(thread) { mutableStateOf("") }
    val categories = entries.map { PresentationPolicy.timelineKind(it.optString("type")) }.distinct()
    val visible = entries.withIndex().filter { (_, entry) ->
        val kind = PresentationPolicy.timelineKind(entry.optString("type"))
        category.isBlank() || kind == category
    }
    fun readPage(index: Int) {
        val id = thread ?: return
        val args = JSONObject().put("threadId", id).put("limit", 50)
        if (index > 0) {
            val previousCursor = data[prefix + (index - 1)]?.optString("nextCursor").orEmpty()
            if (previousCursor.isBlank()) return
            args.put("cursor", previousCursor)
        }
        vm.refresh(prefix + index, "thread/timeline/list", args)
    }
    LaunchedEffect(thread) { readPage(0) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CodexIconAction(onClick = onDismiss) {
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
                if (currentKey in loading) AtlasOperationProgress("正在读取会话时间线")
                if (currentKey !in loading) errors[currentKey]?.let {
                    CodexNotice(PresentationPolicy.readableError(it), NoticeTone.Error,
                        actionLabel = "重试当前页", onAction = { readPage(page) })
                }
                if (entries.isNotEmpty()) {
                    if (categories.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CodexFilterChip(selected = category.isBlank(), onClick = { category = "" },
                            label = { Text("全部") }, modifier = Modifier.heightIn(min = 48.dp))
                        categories.forEach { kind ->
                            CodexFilterChip(selected = category == kind, onClick = { category = kind },
                                label = { Text(kind) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (thread == null) {
                        item { CodexEmptyState("先选择会话", "时间线属于当前任务记录。") }
                    } else if (entries.isEmpty() && currentKey !in loading && currentKey !in errors) {
                        item {
                            if (data[currentKey] != null) CodexEmptyState("尚无时间线记录",
                                "若内核未启用实验接口，请返回普通回合历史查看已保存内容。")
                            else CodexRecoveryState("时间线尚未读取", "读取当前会话记录。",
                                "读取记录", { readPage(page) })
                        }
                    }
                    if (entries.isNotEmpty()) item {
                        AtlasListHeading("筛选结果（已加载 ${entries.size} 项）", visible.size)
                        if (visible.isEmpty()) CodexEmptyState("未找到匹配事件",
                            "可清除筛选，或继续加载更早的记录。",
                            actionLabel = "清除筛选", onAction = { category = "" },
                            artworkRes = R.drawable.ic_empty_no_results)
                    }
                    itemsIndexed(visible, key = { _, record -> record.value.optLong("position") }) { _, record ->
                        val index = record.index
                        val entry = record.value
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
                            CodexSecondaryButton(onClick = {
                                val nextPage = page + 1
                                page = nextPage
                                readPage(nextPage)
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
