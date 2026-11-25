package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject

@Composable
internal fun MemoryManagementSheet(vm: CodexViewModel, threadId: String?, onDismiss: () -> Unit) {
    val operation = rememberCapabilityOperation(vm)
    var status by remember(threadId) { mutableStateOf<JSONObject?>(null) }
    var mode by remember(threadId) { mutableStateOf("") }
    var effectiveMemory by remember(threadId) { mutableStateOf<Boolean?>(null) }
    var confirm by remember(threadId) { mutableStateOf<String?>(null) }
    var reader by remember(threadId) { mutableStateOf(false) }
    suspend fun refresh() {
        val session = requireNotNull(vm.bridge.connectedSession.value) { "请先连接内核" }
        effectiveMemory = readCoreFeatureSwitches(vm, session, threadId).single { it.name == "memories" }.enabled
        status = if (threadId == null) vm.capabilityRequest("memory/status", expectedSession = session)
        else vm.capabilityRequest("thread/memory/read", JSONObject().put("threadId", threadId), expectedSession = session)
    }
    LaunchedEffect(vm, threadId) { operation.launch { refresh() } }
    CodexDetailSheet(if (threadId == null) "全局记忆管理" else "会话记忆",
        onDismiss = { if (!operation.busy) onDismiss() }, startExpanded = true,
        subtitle = if (threadId == null) "查看内核记忆就绪情况及重置入口。" else "查看这条会话当前可读取的记忆摘要。") {
        CapabilityFeedback(operation, "正在同步记忆")
        effectiveMemory?.let { enabled ->
            Text(if (enabled) "内核记忆功能已开启" else "内核记忆功能已关闭 · 可在设置中的功能开关管理", color = Ink.muted)
        }
        if (threadId == null) {
            status?.let {
                Text(if (it.optBoolean("v2Ready")) "记忆已就绪" else "记忆正在积累",
                    style = MaterialTheme.typography.titleMedium)
                Text("已汇总会话 ${it.optInt("v2ConsolidatedThreads")} 条", color = Ink.muted)
            }
            CodexTextAction(onClick = { confirm = "reset" }, enabled = !operation.busy && status != null) {
                Text("重置全局记忆", color = Ink.red)
            }
        } else {
            val summary = status?.value("summary").orEmpty()
            if (status != null) {
                if (summary.isBlank()) Text("当前没有可读取的记忆摘要", color = Ink.muted)
                else {
                    SelectionContainer { Text(summary.take(800)) }
                    if (summary.length > 800) CodexTextAction(onClick = { reader = true }) { Text("查看完整摘要") }
                }
            }
            if (mode.isNotBlank()) Text(if (mode == "enabled") "会话记忆已开启" else "会话记忆已停用")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CodexSecondaryButton(onClick = { confirm = "enabled" },
                    enabled = !operation.busy && effectiveMemory == true && mode != "enabled") { Text("开启记忆") }
                CodexTextAction(onClick = { confirm = "disabled" },
                    enabled = !operation.busy && mode != "disabled") { Text("停用记忆") }
            }
        }
        CodexTextAction(onClick = { operation.launch { refresh() } }, enabled = !operation.busy) { Text("刷新记忆") }
    }
    if (reader) FullTextReaderDialog("完整记忆摘要", status?.value("summary").orEmpty()) { reader = false }
    confirm?.let { action ->
        val reset = action == "reset"
        val enable = action == "enabled"
        CodexConfirmDialog(
            if (reset) "重置全局记忆" else if (enable) "开启会话记忆" else "停用会话记忆",
            if (reset) "将清空当前内核积累的记忆，已有会话历史会保留。"
            else if (enable) "允许本会话使用记忆，并按当前全局设置积累记忆。"
            else "停用这条会话的记忆功能。",
            confirmLabel = if (reset) "确认重置" else if (enable) "开启" else "停用", destructive = reset,
            onConfirm = {
                confirm = null
                operation.launch {
                    if (reset) {
                        vm.capabilityRequest("memory/reset", null)
                        operation.receipt = "全局记忆已重置"
                    } else {
                        check(threadId == vm.activeThread.value && !vm.threadSwitching.value) { "当前会话已切换" }
                        vm.capabilityRequest("thread/memoryMode/set", JSONObject().put("threadId", threadId).put("mode", action))
                        mode = action
                    }
                    status = null
                    refresh()
                }
            }, onDismiss = { confirm = null })
    }
}
