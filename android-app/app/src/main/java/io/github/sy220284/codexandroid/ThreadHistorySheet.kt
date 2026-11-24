package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** 历史分页、回合条目与明确范围回退；全部使用官方线程请求。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadHistorySheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val thread by vm.activeThread.collectAsState()
    val busy by vm.running.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var page by remember(thread) { mutableIntStateOf(0) }
    var target by remember(thread) { mutableStateOf<String?>(null) }
    var inspected by remember(thread) { mutableStateOf<String?>(null) }
    var fullReader by remember(thread) { mutableStateOf<String?>(null) }
    var showAdvanced by remember(thread) { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMarkdown by remember(thread) { mutableStateOf<String?>(null) }
    var exportBusy by remember(thread) { mutableStateOf(false) }
    var exportStatus by remember(thread) { mutableStateOf("") }
    val exportFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val snapshot = exportMarkdown
        if (uri != null && snapshot != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("无法创建导出文件")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                    }
                    exportStatus = "会话 Markdown 文件已保存"
                } catch (failure: Exception) {
                    exportStatus = "保存失败：" + (failure.message ?: "请选择其他保存位置")
                } finally {
                    exportMarkdown = null
                }
            }
        } else exportMarkdown = null
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.border) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 740.dp)
            .navigationBarsPadding().padding(horizontal = 19.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("回合历史与回退", style = MaterialTheme.typography.headlineSmall)
            Text("历史来自实际会话。回退仅修改保存的会话记录，已写入的本地文件不会自动恢复。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { showAdvanced = true }, enabled = thread != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                CodexIcon(CodexGlyph.Timeline, null)
                Spacer(Modifier.width(8.dp))
                Text("查看完整时间线（实验）")
            }
            OutlinedButton(
                onClick = {
                    val target = thread
                    if (!target.isNullOrBlank()) {
                        exportBusy = true
                        exportStatus = ""
                        scope.launch {
                            try {
                                val payload = vm.bridge.request("thread/read", JSONObject()
                                    .put("threadId", target).put("includeTurns", true))
                                val markdown = withContext(Dispatchers.Default) {
                                    ThreadMarkdownExporter.render(payload)
                                }
                                exportMarkdown = markdown
                                exportFilePicker.launch("Codex-会话记录.md")
                            } catch (failure: Exception) {
                                exportStatus = "导出失败：" +
                                    (failure.message ?: "请检查会话连接")
                            } finally {
                                exportBusy = false
                            }
                        }
                    }
                },
                enabled = !exportBusy && thread != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("thread-export-markdown")
            ) {
                CodexIcon(CodexGlyph.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (exportBusy) "正在整理会话记录…" else "导出会话 Markdown")
            }
            if (exportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (exportStatus.isNotBlank()) Text(
                exportStatus, style = MaterialTheme.typography.bodySmall,
                color = if (exportStatus.startsWith("导出失败") ||
                    exportStatus.startsWith("保存失败")) Ink.red else Ink.jade
            )
            if (thread == null) RequiresThreadPanel("回合历史") { onDismiss() }
            else {
                if (key in loading || prefix + "0" in loading)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                errors[key]?.let { ErrorText(it) }
                errors["threadRevert"]?.let { ErrorText(it) }
                if (turns.isEmpty() && pages.isNotEmpty() && key !in loading && prefix + "0" !in loading) {
                    CodexEmptyState("暂无历史回合", "该会话可能尚未执行任务。")
                }
                LazyColumn(modifier = Modifier.weight(1f),
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
                                OutlinedButton(onClick = {
                                    inspected = id
                                    vm.refresh("turnItems:" + id, "thread/items/list",
                                        JSONObject().put("threadId", thread)
                                            .put("turnId", id).put("limit", 100))
                                }, modifier = Modifier.weight(1f)) { Text("查看条目") }
                                OutlinedButton(onClick = { target = id },
                                    enabled = !busy && "threadRevert" !in loading,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red)
                                ) { Text("从此回合回退") }
                            }
                            if (inspected == id) {
                                val entryKey = "turnItems:" + id
                                if (entryKey in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                errors[entryKey]?.let { ErrorText(it) }
                                data[entryKey]?.let { payload ->
                                    Text("已读取 ${records(payload).size} 条历史记录",
                                        color = Ink.muted)
                                    OutlinedButton(onClick = { fullReader = readableTurnItems(payload) }) {
                                        Text("阅读任务执行记录")
                                    }
                                    records(payload).filter { it.optBoolean("outputDeferred") }
                                        .forEach { entry ->
                                            val itemId = entry.optJSONObject("item")
                                                ?.optString("id").orEmpty()
                                            if (itemId.isNotBlank()) {
                                                val detailKey = "turnItemRead:" + id + ":" + itemId
                                                OutlinedButton(
                                                    onClick = {
                                                        vm.refresh(detailKey, "thread/items/read",
                                                            JSONObject().put("threadId", thread)
                                                                .put("turnId", id)
                                                                .put("itemIds", JSONArray().put(itemId)))
                                                    }, modifier = Modifier.fillMaxWidth(),
                                                    enabled = detailKey !in loading
                                                ) { Text("读取完整执行内容") }
                                                if (detailKey in loading)
                                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                                errors[detailKey]?.let { ErrorText(it) }
                                                data[detailKey]?.let { detail ->
                                                    OutlinedButton(
                                                        onClick = { fullReader = readableTurnItems(detail) }
                                                    ) { Text("打开完整输出") }
                                                }
                                            }
                                        }
                                }
                            }
                        }
                    }
                    if (next.isNotBlank()) {
                        item {
                            OutlinedButton(onClick = {
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
    target?.let { turnId ->
        CodexConfirmDialog(
            title = "确认回退会话历史",
            message = "所选回合及其之后的所有会话内容将从持久记录中删除。已经修改的本地文件仍保持现状。请先备份重要内容。",
            detail = "将从所选回合开始移除后续会话内容。已修改的本机文件会保留。",
            confirmLabel = "确认删除该回合及之后历史", destructive = true,
            onConfirm = {
                thread?.let { id ->
                    vm.rpc("threadRevert", "thread/revert",
                        JSONObject().put("threadId", id).put("beforeTurnId", turnId),
                        after = {
                            page = 0
                            vm.refresh(prefix + "0", "thread/turns/list",
                                JSONObject().put("threadId", id).put("limit", 40)
                                    .put("sortDirection", "desc"))
                            vm.chooseThread(id)
                        })
                }
                target = null
            },
            onDismiss = { target = null }
        )
    }
    fullReader?.let { raw ->
        FullTextReaderDialog("会话执行记录", raw) { fullReader = null }
    }
    if (showAdvanced) AdvancedThreadTimelineDialog(vm) { showAdvanced = false }
}

/** 为历史正文阅读器提取真正的事件内容，避免把 RPC 结构当成正文。 */
private fun readableTurnItems(payload: JSONObject): String {
    val items = records(payload)
    if (items.isEmpty()) return "该回合没有可阅读的文本记录。"
    return items.mapIndexed { index, record ->
        val item = record.optJSONObject("item") ?: record
        val kind = PresentationPolicy.timelineKind(item.optString("type"))
        val text = listOf("text", "message", "command", "summary").firstNotNullOfOrNull {
            PresentationPolicy.meaningful(item.optString(it)).takeIf { value -> value.isNotEmpty() }
        }.orEmpty()
        "第 ${index + 1} 项 · $kind" +
            if (text.isNotEmpty()) "\n$text" else "\n已记录操作状态"
    }.joinToString("\n\n")
}
