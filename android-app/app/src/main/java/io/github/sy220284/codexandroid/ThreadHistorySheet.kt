package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException

/** 历史分页、回合条目与明确范围回退；全部使用官方线程请求。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadHistorySheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val thread by vm.activeThread.collectAsStateWithLifecycle()
    val busy by vm.running.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var page by remember(thread) { mutableIntStateOf(0) }
    var target by remember(thread) { mutableStateOf<String?>(null) }
    var inspected by remember(thread) { mutableStateOf<String?>(null) }
    var fullReader by remember(thread) { mutableStateOf<String?>(null) }
    var showAdvanced by remember(thread) { mutableStateOf(false) }
    val historyListState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMarkdown by remember(thread) { mutableStateOf<ThreadExportSnapshot?>(null) }
    var exportCount by remember(thread) { mutableIntStateOf(0) }
    var exportJob by remember(thread) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exportBusy by remember(thread) { mutableStateOf(false) }
    var exportStatus by remember(thread) { mutableStateOf("") }
    var includeActivity by remember(thread) { mutableStateOf(false) }
    var confirmActivity by remember(thread) { mutableStateOf<Boolean?>(null) }
    val currentThread by rememberUpdatedState(thread)
    DisposableEffect(thread) {
        onDispose { exportJob?.cancel() }
    }
    DisposableEffect(exportMarkdown) {
        val snapshot = exportMarkdown
        onDispose { snapshot?.close() }
    }
    val exportFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val snapshot = exportMarkdown
        if (uri != null && snapshot != null) {
            scope.launch {
                try {
                    val exportingThread = thread
                    ThreadHistoryExport.save(context, uri, snapshot) {
                        snapshot.owner.current(vm) && vm.activeThread.value == exportingThread
                    }
                    exportStatus = "会话 Markdown 文件已保存"
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    exportStatus = "保存失败：" + (failure.message ?: "请选择其他保存位置")
                } finally {
                    snapshot.close()
                    exportMarkdown = null
                }
            }
        } else {
            snapshot?.close()
            exportMarkdown = null
        }
    }
    fun exportConversation(toClipboard: Boolean, activity: Boolean) {
        val owner = thread ?: return
        if (exportBusy || busy || exportMarkdown != null) return
        val operation = vm.captureTaskOwner() ?: run {
            exportStatus = "导出失败：运行核心尚未连接"
            return
        }
        exportBusy = true
        exportStatus = ""
        exportCount = 0
        exportJob = scope.launch {
            var prepared: ThreadExportSnapshot? = null
            try {
                val snapshot = ThreadHistoryExport.prepare(context, vm, owner, operation, activity) { count ->
                    withContext(Dispatchers.Main) {
                        if (currentThread == owner) exportCount = count
                    }
                }
                prepared = snapshot
                operation.requireCurrent(vm)
                check(vm.activeThread.value == owner) { "会话已切换，请重新操作" }
                if (toClipboard) {
                    val markdown = withContext(Dispatchers.IO) { snapshot.clipboardText() }
                    val copied = ConversationClipboard.copy(context, markdown, CopyContentFormat.Literal) {
                        operation.current(vm) && vm.activeThread.value == owner
                    }
                    if (currentThread == owner) exportStatus = if (copied) "已复制完整会话" else "复制失败：可尝试导出文件"
                } else {
                    exportMarkdown = snapshot
                    exportFilePicker.launch("Codex-会话记录.md")
                    prepared = null
                }
            } catch (timeout: TimeoutCancellationException) {
                if (currentThread == owner) exportStatus = "导出失败：读取历史超时，请重试"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (currentThread == owner) exportStatus = "导出失败：" + (failure.message ?: "请检查会话连接")
            } finally {
                if (prepared != null && exportMarkdown === prepared) exportMarkdown = null
                prepared?.close()
                if (currentThread == owner) exportBusy = false
            }
        }
    }
    val prefix = "turnHistory:" + (thread ?: "none") + ":"
    val pages = (0..page).mapNotNull { data[prefix + it] }
    val turns = pages.flatMap { records(it) }.distinctBy { it.optString("id") }
    val next = pages.lastOrNull()?.optString("nextCursor").orEmpty()
    val key = prefix + page
    LaunchedEffect(thread) {
        if (thread != null) {
            vm.refresh(prefix + "0", "thread/turns/list",
                JSONObject().put("threadId", thread).put("limit", 40)
                    .put("sortDirection", "desc"))
        }
    }
    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        fullScreen = true, containerColor = Ink.canvas,
        onBackRequest = if (showAdvanced) ({ showAdvanced = false }) else null) {
        if (showAdvanced) AdvancedThreadTimelineDialog(vm) { showAdvanced = false }
        else Column(Modifier.fillMaxWidth().fillMaxHeight()
            .navigationBarsPadding().padding(horizontal = 19.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AtlasSheetHeader("回合历史与回退", onDismiss)
            Text("历史来自实际会话。回退仅修改保存的会话记录，已写入的本地文件不会自动恢复。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            CodexSecondaryButton(onClick = { showAdvanced = true }, enabled = thread != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                CodexIcon(CodexGlyph.Timeline, null)
                Spacer(Modifier.width(8.dp))
                Text("查看完整时间线（实验）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("包含执行记录", Modifier.weight(1f), color = Ink.muted,
                    style = MaterialTheme.typography.bodyMedium)
                WhiteSwitch(checked = includeActivity, onCheckedChange = { includeActivity = it },
                    enabled = !exportBusy && !busy && exportMarkdown == null && thread != null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexSecondaryButton(onClick = {
                    if (includeActivity) confirmActivity = true else exportConversation(true, false)
                }, enabled = !exportBusy && !busy && exportMarkdown == null && thread != null, modifier = Modifier.weight(1f)
                    .heightIn(min = 48.dp).testTag("thread-copy-markdown")) {
                    Text("复制会话")
                }
                CodexSecondaryButton(onClick = {
                    if (includeActivity) confirmActivity = false else exportConversation(false, false)
                }, enabled = !exportBusy && !busy && exportMarkdown == null && thread != null, modifier = Modifier.weight(1f)
                    .heightIn(min = 48.dp).testTag("thread-export-markdown")) {
                    Text("导出会话 Markdown", maxLines = 2)
                }
            }
            if (busy) Text("当前任务结束后可复制或导出完整记录", color = Ink.muted,
                style = MaterialTheme.typography.labelSmall)
            if (exportBusy) {
                AtlasOperationProgress("正在整理会话记录 · 已读取 $exportCount 条")
                CodexSecondaryButton(onClick = { exportJob?.cancel(); exportStatus = "已取消整理会话记录" },
                    modifier = Modifier.fillMaxWidth()) { Text("取消整理") }
            }
            if (exportStatus.isNotBlank()) Text(
                exportStatus, style = MaterialTheme.typography.bodySmall,
                color = if (exportStatus.startsWith("导出失败") ||
                    exportStatus.startsWith("保存失败") || exportStatus.startsWith("复制失败")) Ink.red else Ink.jade
            )
            if (thread == null) RequiresThreadPanel("回合历史") { onDismiss() }
            else {
                if (key in loading || prefix + "0" in loading)
                    AtlasOperationProgress("正在读取历史回合")
                errors[key]?.let { ErrorText(it) }
                errors["threadRevert"]?.let { ErrorText(it) }
                if (turns.isEmpty() && pages.isNotEmpty() && key !in loading && prefix + "0" !in loading) {
                    CodexEmptyState("暂无历史回合", "该会话可能尚未执行任务。")
                }
                LazyColumn(state = historyListState, modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)) {
                    itemsIndexed(turns, key = { _, item -> item.optString("id") }) { index, turn ->
                        val id = turn.optString("id")
                        MaterialPanel(Modifier.fillMaxWidth()) {
                            Text("第 ${turns.size - index} 回合", style = MaterialTheme.typography.titleMedium)
                            turn.optString("status").takeIf { it.isNotBlank() }?.let {
                                val status = PresentationPolicy.timelineStatus(it)
                                if (status.isNotEmpty()) StatusPill(status, good = status != "失败")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CodexSecondaryButton(onClick = {
                                    inspected = id
                                    vm.refresh("turnItems:" + id, "thread/items/list",
                                        JSONObject().put("threadId", thread)
                                            .put("turnId", id).put("limit", 100))
                                }, modifier = Modifier.weight(1f)) { Text("查看条目") }
                                CodexSecondaryButton(onClick = { target = id },
                                    enabled = !busy && "threadRevert" !in loading,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red)
                                ) { Text("从此回合回退") }
                            }

                        }
                    }
                    if (next.isNotBlank()) {
                        item {
                            CodexSecondaryButton(onClick = {
                                val nextPage = page + 1
                                page = nextPage
                                vm.refresh(prefix + nextPage, "thread/turns/list",
                                    JSONObject().put("threadId", thread)
                                        .put("cursor", next).put("limit", 40)
                                        .put("sortDirection", "desc"))
                            }, enabled = key !in loading, modifier = Modifier.fillMaxWidth()) {
                                Text("加载更早回合")
                            }
                        }
                    }
                }
            }
        }
    }
    confirmActivity?.let { toClipboard ->
        CodexConfirmDialog(title = "包含执行记录",
            message = "执行记录可能包含命令参数、文件路径、工具输入和输出中的敏感信息。请确认后再复制或保存。",
            confirmLabel = if (toClipboard) "确认复制" else "确认导出",
            onConfirm = { confirmActivity = null; exportConversation(toClipboard, true) },
            onDismiss = { confirmActivity = null })
    }
    if (fullReader == null) inspected?.let { id ->
        CodexInfoDialog("回合条目详情", onDismiss = { inspected = null }) {
        val entryKey = "turnItems:" + id
        if (entryKey in loading) AtlasLoadingResults("正在读取回合条目", placeholderRows = 2)
        errors[entryKey]?.let { ErrorText(it) }
        data[entryKey]?.let { payload ->
            Text("已读取 ${records(payload).size} 条历史记录",
                color = Ink.muted)
            CodexSecondaryButton(onClick = { fullReader = readableTurnItems(payload) }) {
                Text("阅读任务执行记录")
            }
            records(payload).filter { it.optBoolean("outputDeferred") }
                .forEach { entry ->
                    val itemId = entry.optJSONObject("item")
                        ?.optString("id").orEmpty()
                    if (itemId.isNotBlank()) {
                        val detailKey = "turnItemRead:" + id + ":" + itemId
                        CodexSecondaryButton(
                            onClick = {
                                vm.refresh(detailKey, "thread/items/read",
                                    JSONObject().put("threadId", thread)
                                        .put("turnId", id)
                                        .put("itemIds", JSONArray().put(itemId)))
                            }, modifier = Modifier.fillMaxWidth(),
                            enabled = detailKey !in loading
                        ) { Text("读取完整执行内容") }
                        if (detailKey in loading)
                            AtlasOperationProgress("正在读取完整执行内容")
                        errors[detailKey]?.let { ErrorText(it) }
                        data[detailKey]?.let { detail ->
                            CodexSecondaryButton(
                                onClick = { fullReader = readableTurnItems(detail) }
                            ) { Text("打开完整输出") }
                        }
                    }
                }
        }
            if (entryKey !in loading && data[entryKey] == null && errors[entryKey] == null)
                AtlasFieldFeedback("暂无回合条目")
        }
    }
    target?.let { turnId ->
        CodexConfirmDialog(
            title = "确认回退会话历史",
            message = "所选回合及其之后的所有会话内容将从持久记录中删除。已经修改的本地文件仍保持现状。请先备份重要内容。",
            detail = "将从所选回合开始移除后续会话内容。已修改的本机文件会保留。",
            confirmLabel = "确认删除该回合及之后历史", destructive = true,
            onConfirm = {
                thread?.let { id ->
                    vm.conversationActions.revert(id, turnId)
                    onDismiss()
                }
                target = null
            },
            onDismiss = { target = null }
        )
    }
    fullReader?.let { raw ->
        FullTextReaderDialog("会话执行记录", raw) { fullReader = null }
    }
}

/** 为历史正文阅读器提取真正的事件内容，避免把 RPC 结构当成正文。 */
private fun readableTurnItems(payload: JSONObject): String {
    val items = records(payload)
    if (items.isEmpty()) return "该回合没有可阅读的文本记录。"
    return items.mapIndexed { index, record ->
        val item = AgentReplyContract.sanitize(record.optJSONObject("item") ?: record)
        val kind = PresentationPolicy.timelineKind(item.optString("type"))
        val text = listOf("text", "message", "command", "summary").firstNotNullOfOrNull {
            PresentationPolicy.meaningful(item.optString(it)).takeIf { value -> value.isNotEmpty() }
        }.orEmpty()
        "第 ${index + 1} 项 · $kind" +
            if (text.isNotEmpty()) "\n$text" else "\n已记录操作状态"
    }.joinToString("\n\n")
}
