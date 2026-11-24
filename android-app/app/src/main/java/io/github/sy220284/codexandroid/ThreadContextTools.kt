package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val running by vm.running.collectAsState()
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
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
    fun openSubpage(route: String) {
        // The parent owns the pending destination and opens it after this sheet is removed.
        onDismiss()
        navigate(route)
    }
    val forkId = data["threadFork"]?.optJSONObject("thread")?.optString("id")
        ?.takeIf { it.isNotBlank() }
    CodexDetailSheet(title = "会话工具", onDismiss = onDismiss, startExpanded = true,
        subtitle = "管理当前任务的历史、资料和审查；复制分支与上下文整理位于下方。") {
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Thread, null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前会话", style = MaterialTheme.typography.titleMedium)
                    Text(if (threadId.isNullOrBlank()) "尚未创建" else "本机工作区",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(if (running) "执行中" else "可操作", !running)
            }
            CodexDetailTrigger("查看会话信息", onClick = {
                threadId?.let {
                    vm.refresh("threadContext", "thread/read",
                        JSONObject().put("threadId", it))
                }
                showDetail = true
            })
            if (showDetail) CodexInfoDialog(
                title = "当前会话信息",
                onDismiss = { showDetail = false }
            ) {
                val payload = data["threadContext"]
                if ("threadContext" in loading) {
                    CodexSkeletonRows(2)
                } else if (errors["threadContext"] != null) {
                    AtlasFieldFeedback(PresentationPolicy.readableError(errors.getValue("threadContext")), isError = true)
                } else if (payload == null) {
                    AtlasFieldFeedback("暂无会话信息，可关闭后重新读取")
                } else {
                    val info = payload.optJSONObject("thread")
                    val title = PresentationPolicy.threadTitle(
                        info?.optString("name"), info?.optString("preview"))
                    Text("会话名称：$title", style = MaterialTheme.typography.bodyMedium)
                    Text("已保存回合：" + (info?.optJSONArray("turns")?.length() ?: 0),
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("当前任务工具", style = MaterialTheme.typography.titleMedium)
            Text("查看当前目标、后续指令、历史记录及关联资料。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            AtlasSettingsActionRow("管理任务目标", "查看目标与完成条件", CodexGlyph.Flag,
                onClick = { openSubpage("goals") }, enabled = !threadId.isNullOrBlank(),
                modifier = Modifier.testTag("thread-context-goals"), outlined = false)
            DividerLine()
            AtlasSettingsActionRow("后续指令", "查看等待执行的指令", CodexGlyph.ViewList,
                onClick = { openSubpage("queue") }, enabled = !threadId.isNullOrBlank(), outlined = false)
            DividerLine()
            AtlasSettingsActionRow("回合历史与回退", "查看执行记录和历史操作", CodexGlyph.History,
                onClick = { openSubpage("history-tools") },
                enabled = !threadId.isNullOrBlank() && !running, outlined = false)
            DividerLine()
            AtlasSettingsActionRow("会话关联资料", "管理附件和相关资料", CodexGlyph.Attachment,
                onClick = { openSubpage("thread-attachments") }, enabled = !threadId.isNullOrBlank(), outlined = false)
            DividerLine()
            AtlasSettingsActionRow("代码审查", "选择审查范围与要求", CodexGlyph.FactCheck,
                onClick = { openSubpage("review") }, enabled = !threadId.isNullOrBlank() && !running,
                modifier = Modifier.testTag("thread-context-review"), outlined = false)
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.CallSplit, null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Text("复制会话分支", style = MaterialTheme.typography.titleMedium)
            }
            Text("保留当前会话记录，生成新的独立分支，适合尝试另一种解决路线。",
                color = Ink.muted)
            CodexSecondaryButton(onClick = { action = "fork" },
                enabled = !running && !busy && !threadId.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("创建分支")
            }
            forkId?.let { fork ->
                CodexNotice("服务端已建立新的会话分支。", NoticeTone.Success)
                CodexSecondaryButton(onClick = {
                    vm.chooseThread(fork) { openSubpage("chat") }
                }, enabled = !switching && !firstThreadStarting) {
                    Text("打开新建分支")
                }
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Compress, null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Text("压缩长会话上下文", style = MaterialTheme.typography.titleMedium)
            }
            Text("整理较早的对话内容后，部分细节可能无法继续直接引用。",
                color = Ink.muted)
            CodexSecondaryButton(onClick = { action = "compact" },
                enabled = !running && !busy && !threadId.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("整理上下文")
            }
            if (data["threadCompact"] != null)
                CodexNotice("服务端已接受压缩请求，请查看任务流确认处理结果。")
        }
        if (busy) AtlasOperationProgress("正在处理会话操作")
        listOf("threadFork", "threadCompact", "threadContext").forEach { key ->
            errors[key]?.let { ErrorText(it) }
        }
        CodexTextAction(onClick = { openSubpage("threads") }, modifier = Modifier.fillMaxWidth()) {
            Text("前往会话资料库，管理名称与归档")
        }
        Spacer(Modifier.height(12.dp))
    }
    if (action.isNotBlank()) {
        val kind = action
        CodexConfirmDialog(
            title = if (kind == "fork") "创建会话分支" else "整理长会话上下文",
            message = if (kind == "fork")
                "内核将复制当前会话，生成一个新线程，原会话保持不变。"
            else "内核会压缩当前会话的历史上下文。处理后的上下文摘要可能省略部分细节。",
            detail = if (threadId.isNullOrBlank()) "请先选择会话" else "操作将作用于当前会话",
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
