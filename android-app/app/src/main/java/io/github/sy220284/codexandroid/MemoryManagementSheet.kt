package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@Composable
internal fun MemoryManagementSheet(vm: CodexViewModel, threadId: String?, onDismiss: () -> Unit) {
    val session = vm.bridge.connectedSession.collectAsStateWithLifecycle().value
    val account = vm.catalogWarmup.accountRevision.collectAsStateWithLifecycle().value
    val selectedThread by vm.activeThread.collectAsStateWithLifecycle()
    val switching by vm.threadSwitching.collectAsStateWithLifecycle()
    val initialOwner = remember(threadId) { session to account }
    val operation = remember(vm, threadId, session, account) { CapabilityOperation(vm) }
    var status by remember(threadId, session, account) { mutableStateOf<JSONObject?>(null) }
    var mode by remember(threadId, session, account) { mutableStateOf("") }
    var effectiveMemory by remember(threadId, session, account) { mutableStateOf<Boolean?>(null) }
    var confirm by remember(threadId, session, account) { mutableStateOf<String?>(null) }
    var reader by remember(threadId, session, account) { mutableStateOf(false) }
    fun owns(): Boolean = session != null && vm.bridge.connectedSession.value == session &&
        vm.catalogWarmup.accountRevision.value == account &&
        (threadId == null || (vm.activeThread.value == threadId && !vm.threadSwitching.value))
    suspend fun request(method: String, params: JSONObject? = JSONObject()): JSONObject {
        check(owns()) { "记忆所属会话或账号已变化，请重新打开" }
        val result = vm.capabilityRequest(method, params, sendPermit = ::owns, expectedSession = session)
        check(owns()) { "记忆所属会话或账号已变化，请重新打开" }
        return result
    }
    suspend fun refresh() {
        val epoch = requireNotNull(session) { "请先连接内核" }
        check(owns()) { "记忆所属会话或账号已变化，请重新打开" }
        val enabled = readCoreFeatureSwitches(vm, epoch, threadId, account).single { it.name == "memories" }.enabled
        val result = if (threadId == null) request("memory/status")
        else request("thread/memory/read", JSONObject().put("threadId", threadId))
        effectiveMemory = enabled
        status = result
    }
    LaunchedEffect(session, account, selectedThread, switching) {
        if ((initialOwner.first != null && session != initialOwner.first) || account != initialOwner.second ||
            (threadId != null && selectedThread != threadId)) onDismiss()
        else if (owns()) operation.launch { refresh() }
    }
    CodexDetailSheet(if (threadId == null) "全局记忆管理" else "会话记忆",
        onDismiss = { if (!operation.busy) onDismiss() }, startExpanded = true,
        subtitle = if (threadId == null) "查看内核记忆就绪情况及重置入口。" else "查看这条会话当前可读取的记忆摘要。") {
        CapabilityFeedback(operation, "正在同步记忆")
        if (session == null) CodexNotice("运行核心尚未连接，连接恢复后可读取记忆。",
            NoticeTone.Warning, actionLabel = "重新连接", onAction = { vm.connect() })
        effectiveMemory?.let { enabled ->
            Text(if (enabled) "内核记忆功能已开启" else "内核记忆功能已关闭 · 可在设置中的功能开关管理", color = Ink.muted)
        }
        if (threadId == null) {
            status?.let {
                Text(if (it.optBoolean("v2Ready")) "记忆已就绪" else "记忆正在积累",
                    style = MaterialTheme.typography.titleMedium)
                Text("已汇总会话 ${it.optInt("v2ConsolidatedThreads")} 条", color = Ink.muted)
            }
            CodexTextAction(onClick = { confirm = "reset" }, enabled = owns() && !operation.busy && status != null) {
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
            CodexAdaptiveActions(
                first = { modifier ->
                    CodexSecondaryButton(onClick = { confirm = "enabled" }, modifier = modifier,
                        enabled = owns() && !operation.busy && effectiveMemory == true && mode != "enabled") { Text("开启记忆") }
                },
                second = { modifier ->
                    CodexSecondaryButton(onClick = { confirm = "disabled" }, modifier = modifier,
                        enabled = owns() && !operation.busy && mode != "disabled") { Text("停用记忆") }
                }
            )
        }
        CodexTextAction(onClick = { operation.launch { refresh() } }, enabled = owns() && !operation.busy) { Text("刷新记忆") }
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
                        request("memory/reset", null)
                        status = null
                        refresh()
                        operation.receipt = "核心已确认重置，记忆状态已重新读取"
                    } else {
                        request("thread/memoryMode/set", JSONObject().put("threadId", threadId).put("mode", action))
                        status = null
                        refresh()
                        mode = action
                        operation.receipt = "核心已接收记忆设置，摘要已重新读取"
                    }
                }
            }, onDismiss = { confirm = null })
    }
}
