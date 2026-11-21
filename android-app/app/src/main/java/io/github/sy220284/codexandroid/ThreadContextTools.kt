package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * High-value thread actions are deliberately separated from prompt composition.
 * Fork and compaction are official RPC actions and only appear on a loaded,
 * idle conversation. A request being accepted does not imply compaction finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadContextTools(
    vm: CodexViewModel,
    navigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val current by vm.activeThread.collectAsState()
    val activeTurn by vm.activeTurn.collectAsState()
    val running by vm.running.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val data by vm.data.collectAsState()
    var action by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf(false) }
    val threadId = current
    LaunchedEffect(threadId) {
        if (!threadId.isNullOrBlank()) {
            vm.refresh("threadContext", "thread/read", JSONObject().put("threadId", threadId))
        }
    }
    val busy = "threadFork" in loading || "threadCompact" in loading
    val forkId = data["threadFork"]?.optJSONObject("thread")?.optString("id")
        ?.takeIf { it.isNotBlank() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.recessed,
        contentColor = Ink.parchment,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 690.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            SectionIntro("THREAD / OPERATIONS", "会话工具",
                "在保留当前工程上下文的前提下复制分支，或整理长任务的历史内容。")
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Forum, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("当前会话", style = MaterialTheme.typography.titleMedium)
                        Text(threadId ?: "尚未创建", color = Ink.muted,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    StatusPill(if (running) "执行中" else "可操作", !running)
                }
                activeTurn?.takeIf { it.isNotBlank() }?.let {
                    Text("当前执行轮次：" + it, color = Ink.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    threadId?.let {
                        vm.refresh("threadContext", "thread/read",
                            JSONObject().put("threadId", it))
                    }
                    showDetail = !showDetail
                }) { Text(if (showDetail) "隐藏会话信息" else "查看会话信息") }
                CodexReveal(showDetail) {
                    data["threadContext"]?.let { payload ->
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(payload.optJSONObject("thread")?.toString(2) ?: payload.toString(2),
                                color = Ink.muted, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CallSplit, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Text("复制会话分支", style = MaterialTheme.typography.titleMedium)
                }
                Text("保留当前会话记录，生成新的独立分支，适合尝试另一种解决路线。",
                    color = Ink.muted)
                OutlinedButton(onClick = { action = "fork" },
                    enabled = !running && !busy && !threadId.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("创建分支")
                }
                forkId?.let { fork ->
                    CodexNotice("服务端已建立新的会话分支。", NoticeTone.Success)
                    OutlinedButton(onClick = {
                        vm.chooseThread(fork); onDismiss(); navigate("chat")
                    }, enabled = !running) {
                        Text("打开分支 · " + fork.take(12))
                    }
                }
            }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Compress, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Text("压缩长会话上下文", style = MaterialTheme.typography.titleMedium)
                }
                Text("由 Codex 核心整理历史上下文，压缩会影响后续可直接引用的细节。",
                    color = Ink.muted)
                OutlinedButton(onClick = { action = "compact" },
                    enabled = !running && !busy && !threadId.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("整理上下文")
                }
                if (data["threadCompact"] != null)
                    CodexNotice("服务端已接受压缩请求，请查看任务流确认处理结果。")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            listOf("threadFork", "threadCompact", "threadContext").forEach { key ->
                errors[key]?.let { ErrorText(it) }
            }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("当前任务工具", style = MaterialTheme.typography.titleMedium)
                Text("和此会话相关的目标、队列与历史操作集中管理。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    onDismiss()
                    navigate("goals")
                }, enabled = !threadId.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Icon(Icons.Outlined.Flag, null)
                    Spacer(Modifier.width(8.dp))
                    Text("管理任务目标")
                }
                OutlinedButton(onClick = {
                    onDismiss()
                    navigate("queue")
                }, enabled = !threadId.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Icon(Icons.Outlined.ViewList, null)
                    Spacer(Modifier.width(8.dp))
                    Text("待办队列（实验）")
                }
                OutlinedButton(onClick = {
                    onDismiss()
                    navigate("history-tools")
                }, enabled = !threadId.isNullOrBlank() && !running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Icon(Icons.Outlined.History, null)
                    Spacer(Modifier.width(8.dp))
                    Text("回合历史与回退")
                }
                OutlinedButton(onClick = {
                    onDismiss()
                    navigate("review")
                }, enabled = !threadId.isNullOrBlank() && !running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Icon(Icons.Outlined.FactCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text("代码审查")
                }
            }
            TextButton(onClick = {
                onDismiss()
                navigate("threads")
            }, modifier = Modifier.fillMaxWidth()) {
                Text("前往会话资料库，管理名称与归档")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (action.isNotBlank()) {
        val kind = action
        CodexConfirmDialog(
            title = if (kind == "fork") "创建会话分支" else "整理长会话上下文",
            message = if (kind == "fork")
                "内核将复制当前会话，生成一个新线程，原会话保持不变。"
            else "内核会压缩当前会话的历史上下文。处理后的上下文摘要可能省略部分细节。",
            detail = "会话：${threadId ?: "未选择"}",
            confirmLabel = if (kind == "fork") "确认创建" else "确认整理",
            onConfirm = {
                if (threadId != null) {
                    if (kind == "fork") {
                        vm.data.value = vm.data.value - "threadFork"
                        vm.rpc("threadFork", "thread/fork",
                            JSONObject().put("threadId", threadId).put("excludeTurns", true),
                            after = {
                                vm.refresh("threads", "thread/list", JSONObject().put("limit", 30))
                            })
                    } else {
                        vm.data.value = vm.data.value - "threadCompact"
                        vm.rpc("threadCompact", "thread/compact/start",
                            JSONObject().put("threadId", threadId))
                    }
                }
                action = ""
            },
            onDismiss = { action = "" }
        )
    }
}
