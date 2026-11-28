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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun FeatureSwitchPage(vm: CodexViewModel) {
    val controller = vm.featureSwitches
    val session by vm.bridge.connectedSession.collectAsStateWithLifecycle()
    val account by vm.catalogWarmup.accountRevision.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val transaction by vm.configTransactionBusy.collectAsStateWithLifecycle()
    val securityBusy by vm.securitySaveBusy.collectAsStateWithLifecycle()
    val transfer by AppDataTransfer.state.collectAsStateWithLifecycle()
    val snapshot = controller.snapshot?.takeIf { it.session == session && it.account == account }
    var filter by rememberSaveable { mutableStateOf("preferred") }
    var category by rememberSaveable { mutableStateOf("all") }
    var picker by remember(session, account) { mutableStateOf("") }
    var confirm by remember(session, account) { mutableStateOf<Pair<CoreFeatureSwitch, Boolean>?>(null) }
    var detail by remember(session, account) { mutableStateOf<String?>(null) }
    var lastReadIdentity by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(session, account, controller.busy) {
        val identity = session?.let { it to account }
        if (identity != null && identity != lastReadIdentity && !controller.busy) {
            lastReadIdentity = identity
            controller.refresh()
        }
    }
    LaunchedEffect(filter, category) { listState.scrollToItem(0) }
    val rows = snapshot?.rows.orEmpty()
    val categories = remember(rows) {
        listOf("all" to "全部类别") + FeatureSwitchPolicy.UseGroup.entries.map { group ->
            group.name to "${group.label} · ${rows.count { FeatureSwitchPolicy.group(it.name) == group }}"
        }
    }
    val grouped = remember(rows, category, filter, snapshot?.managed) {
        rows.filter { FeatureSwitchPolicy.matches(it, filter) &&
            (category == "all" || FeatureSwitchPolicy.group(it.name).name == category) }
            .sortedWith(compareBy<CoreFeatureSwitch> { it.name !in FeatureSwitchPolicy.preferred }
                .thenBy { it.restriction(snapshot?.managed.orEmpty()) != null }.thenBy { it.title })
            .groupBy { FeatureSwitchPolicy.group(it.name) }
    }
    val blocked = controller.busy || configSnapshotBusy(loading, transaction || securityBusy) || transfer.busy
    val feedbackCurrent = session != null && controller.attemptedSession == session && controller.attemptedAccount == account
    LazyColumn(Modifier.fillMaxSize().testTag("feature-switch-list"), state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        item(key = "controls") {
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot != null) {
                    Text("全部 ${rows.size} 项 · 已开启 ${rows.count { it.enabled }} 项",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (filter == "preferred") "常用能力 · 根据实际内核目录显示" else "全部能力 · 包含高级及实验选项",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    CodexTextAction(onClick = { filter = if (filter == "preferred") "all" else "preferred"; category = "all" },
                        modifier = Modifier.testTag("feature-show-all")) { Text(if (filter == "preferred") "查看全部功能" else "返回常用能力") }
                    CodexAdaptiveActions(
                        first = { modifier ->
                            CodexSecondaryButton(onClick = { picker = "category" },
                                modifier = modifier.testTag("feature-category-picker")) {
                                Text(categories.first { it.first == category }.second)
                            }
                        },
                        second = { modifier ->
                            CodexSecondaryButton(onClick = { picker = "status" },
                                modifier = modifier.testTag("feature-status-picker")) {
                                Text(FeatureSwitchPolicy.statusFilters.first { it.first == filter }.second)
                            }
                        })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("显示 ${grouped.values.sumOf { it.size }} 项", Modifier.weight(1f),
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        if (category != "all" || filter != "all") CodexTextAction(onClick = {
                            category = "all"; filter = "all"
                        }) { Text("清除筛选") }
                    }
                }
                if (session == null) CodexNotice("内核未连接；连接后可读取实际功能状态", NoticeTone.Warning)
                if (controller.busy && feedbackCurrent) AtlasOperationProgress("正在读取或保存功能状态")
                if (feedbackCurrent && controller.error.isNotBlank()) CodexNotice(controller.error, NoticeTone.Error)
                if (feedbackCurrent && controller.receipt.isNotBlank()) CodexNotice(controller.receipt, NoticeTone.Success)
                CodexTextAction(onClick = { controller.refresh() }, enabled = session != null && !blocked) {
                    Text("刷新实际状态")
                }
            }
        }
        if (snapshot != null && grouped.isEmpty()) item(key = "empty") {
            Text(if (filter == "preferred") "当前核心未提供常用能力，可查看全部功能。" else "此分类暂无功能", color = Ink.muted)
        }
        for (group in FeatureSwitchPolicy.UseGroup.entries) {
            val entries = grouped[group].orEmpty()
            if (entries.isEmpty()) continue
            item(key = "group-${group.name}") {
                AtlasListHeading(group.label, entries.size, suffix = "项")
            }
            items(entries, key = { it.name }) { row ->
                val restriction = row.restriction(snapshot?.managed.orEmpty())
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                        .codexPressable(role = Role.Button, onClick = { detail = row.name })
                        .testTag("feature-detail-${row.name}"),
                        verticalArrangement = Arrangement.Center) {
                        Text(row.title, style = MaterialTheme.typography.titleMedium)
                        Text(row.stageLabel + " · 默认" + if (row.defaultEnabled) "开启" else "关闭",
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        restriction?.let { Text(it, color = Ink.muted, style = MaterialTheme.typography.labelMedium) }
                    }
                    WhiteSwitch(row.enabled, { enabled ->
                        if (FeatureSwitchPolicy.needsConfirmation(row)) confirm = row to enabled
                        else snapshot?.let { controller.change(row, enabled, it.session, it.account) }
                    }, modifier = Modifier.testTag("feature-switch-${row.name}")
                        .semantics { contentDescription = row.title + "开关" },
                        enabled = !blocked && restriction == null)
                }
                DividerLine()
            }
        }
    }
    if (picker.isNotBlank()) {
        val isCategory = picker == "category"
        val choices = if (isCategory) categories else FeatureSwitchPolicy.statusFilters
        CodexDetailSheet(if (isCategory) "功能类别" else "功能状态", { picker = "" }) {
            choices.forEach { (id, label) ->
                AtlasChoiceRow(label, "", selected = id == if (isCategory) category else filter,
                    onClick = { if (isCategory) category = id else filter = id; picker = "" },
                    modifier = Modifier.testTag("feature-filter-$id"))
            }
        }
    }
    confirm?.let { (row, enabled) ->
        val confirmedSession = snapshot?.session
        CodexConfirmDialog(
            "${if (enabled) "开启" else "关闭"}${row.title}",
            FeatureSwitchPolicy.confirmation(row, enabled),
            confirmLabel = if (enabled) "开启" else "关闭",
            confirmEnabled = confirmedSession != null && !blocked,
            onConfirm = { confirm = null; confirmedSession?.let { controller.change(row, enabled, it, account) } },
            onDismiss = { confirm = null }
        )
    }
    rows.firstOrNull { it.name == detail }?.let { row ->
        FullTextReaderDialog(row.title, buildString {
            append(row.stageLabel + " · 当前" + if (row.enabled) "开启" else "关闭")
            append("\n\n")
            append(row.description.ifBlank { FeatureSwitchPolicy.confirmation(row, !row.enabled) })
            append("\n\n内核标识：${row.name}")
        }) { detail = null }
    }
}
