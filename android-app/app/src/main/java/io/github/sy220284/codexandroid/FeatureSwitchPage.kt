package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun FeatureSwitchPage(vm: CodexViewModel) {
    val controller = vm.featureSwitches
    val session by vm.bridge.connectedSession.collectAsState()
    val loading by vm.loading.collectAsState()
    val transaction by vm.configTransactionBusy.collectAsState()
    val securityBusy by vm.securitySaveBusy.collectAsState()
    val transfer by AppDataTransfer.state.collectAsState()
    val snapshot = controller.snapshot?.takeIf { it.session == session }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var confirm by remember { mutableStateOf<Pair<CoreFeatureSwitch, Boolean>?>(null) }
    var detail by remember { mutableStateOf<CoreFeatureSwitch?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(session) {
        if (session != null && !controller.busy) controller.refresh()
    }
    LaunchedEffect(session, controller.busy) {
        if (session != null && !controller.busy && controller.attemptedSession != session) controller.refresh()
    }
    LaunchedEffect(session) { confirm = null; detail = null }
    LaunchedEffect(query, filter) { listState.scrollToItem(0) }
    val rows = snapshot?.rows.orEmpty()
    val visible = rows.filter { it.matches(query) && when (filter) {
        "preferred" -> it.name in FeatureSwitchPolicy.preferred
        "enabled" -> it.enabled
        "disabled" -> !it.enabled
        "stable" -> it.stage == "stable"
        "experimental" -> it.stage in setOf("beta", "underDevelopment")
        "retired" -> it.stage in setOf("deprecated", "removed")
        else -> true
    } }.sortedWith(compareBy<CoreFeatureSwitch> { it.name !in FeatureSwitchPolicy.preferred }
        .thenBy { it.restriction(snapshot?.managed.orEmpty()) != null }.thenBy { it.title })
    val blocked = controller.busy || configSnapshotBusy(loading, transaction || securityBusy) || transfer.busy
    LazyColumn(Modifier.fillMaxSize().testTag("feature-switch-list"), state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AtlasSearchField(query, { query = it }, "搜索功能、名称或阶段",
                    modifier = Modifier.testTag("feature-switch-search"), onClear = { query = "" })
                if (snapshot != null) {
                    Text("全部 ${rows.size} 项 · 已开启 ${rows.count { it.enabled }} 项",
                        style = MaterialTheme.typography.titleMedium)
                    Text("这里管理全局内核配置。单个项目、账号及运行环境可能有额外限制。",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    listOf(
                        listOf("all" to "全部", "preferred" to "优先", "enabled" to "已开启"),
                        listOf("disabled" to "已关闭", "stable" to "稳定", "experimental" to "实验/开发"),
                        listOf("retired" to "历史功能")
                    ).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { (id, title) ->
                                CodexFilterChip(selected = filter == id, onClick = { filter = id },
                                    label = { Text(title) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (session == null) CodexNotice("内核未连接；连接后可读取实际功能状态", NoticeTone.Error)
                if (controller.busy) AtlasOperationProgress("正在读取或保存功能状态")
                if (controller.error.isNotBlank()) CodexNotice(controller.error, NoticeTone.Error)
                if (controller.receipt.isNotBlank()) CodexNotice(controller.receipt, NoticeTone.Success)
                CodexTextAction(onClick = { controller.refresh() }, enabled = session != null && !blocked) {
                    Text("刷新实际状态")
                }
            }
        }
        if (snapshot != null && visible.isEmpty()) item(key = "empty") {
            Text("没有匹配的功能", color = Ink.muted)
        }
        items(visible, key = { it.name }) { row ->
            val restriction = row.restriction(snapshot?.managed.orEmpty())
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Text(row.stageLabel + " · 默认" + if (row.defaultEnabled) "开启" else "关闭",
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        restriction?.let { Text(it, color = Ink.muted, style = MaterialTheme.typography.labelMedium) }
                        CodexTextAction(onClick = { detail = row }) { Text("查看说明") }
                    }
                    WhiteSwitch(row.enabled, { confirm = row to it },
                        modifier = Modifier.testTag("feature-switch-${row.name}"),
                        enabled = !blocked && restriction == null)
                }
            }
        }
    }
    confirm?.let { (row, enabled) ->
        CodexConfirmDialog(
            "${if (enabled) "开启" else "关闭"}${row.title}",
            FeatureSwitchPolicy.confirmation(row, enabled),
            confirmLabel = if (enabled) "开启" else "关闭",
            onConfirm = { confirm = null; controller.change(row, enabled) },
            onDismiss = { confirm = null }
        )
    }
    detail?.let { row ->
        FullTextReaderDialog(row.title, buildString {
            append(row.stageLabel + " · 当前" + if (row.enabled) "开启" else "关闭")
            append("\n\n")
            append(row.description.ifBlank { FeatureSwitchPolicy.confirmation(row, !row.enabled) })
            append("\n\n内核标识：${row.name}")
        }) { detail = null }
    }
}
