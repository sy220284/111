package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

/**
 * 短选择锚定触发按钮，复杂操作进入页面，风险操作居中确认。
 * 状态与附件使用适合内容的窗口；调用和结果复用真实 ViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineStatusSheet(
    vm: CodexViewModel, onDismiss: () -> Unit, navigate: (String) -> Unit
) {
    val status by vm.bridge.status.collectAsStateWithLifecycle()
    val terminalRunning by vm.terminalSession.collectProjection { it.running }
    val retirement by vm.bridge.retirementBusy.collectAsStateWithLifecycle()
    val retry by vm.recoveryDelayMs.collectAsStateWithLifecycle()
    val restarting by vm.connectionRestarter.busy.collectAsStateWithLifecycle()
    CodexDetailSheet(title = "运行状态", onDismiss = onDismiss) {
        val label = RuntimeStatusPresentation.label(status, retirement, restarting, retry)
        StatusPill(label, tone = if (status == EngineStatus.Connected) StatusTone.Success else StatusTone.Warning)
        if (retirement || restarting || status == EngineStatus.Starting || (status is EngineStatus.Unavailable && retry != null)) {
            AtlasOperationProgress(RuntimeStatusPresentation.detail(status, retirement, restarting, retry))
        } else if (status is EngineStatus.Unavailable) {
            AtlasFieldFeedback(PresentationPolicy.readableError((status as EngineStatus.Unavailable).reason), isError = true)
        }
        if (status != EngineStatus.Connected) {
            AtlasSettingsActionRow("重新连接本机内核", "保持当前会话和草稿", CodexGlyph.Retry,
                onClick = { onDismiss(); vm.connectionRestarter.reconnect() },
                enabled = RuntimeStatusPresentation.canReconnect(status, retirement, restarting), outlined = false)
        }
        if (terminalRunning) {
            AtlasSettingsActionRow("查看正在运行的终端", "返回命令执行现场", CodexGlyph.Terminal,
                onClick = { onDismiss(); navigate("terminal") }, outlined = false)
        }
        AtlasSettingsActionRow("查看诊断和日志", "检查内核及连接状态", CodexGlyph.Troubleshoot,
            onClick = { onDismiss(); navigate("diagnostics") }, outlined = false)
    }
}

@Composable
fun ModelChoiceSurface(vm: CodexViewModel, anchored: Boolean = false, onDismiss: () -> Unit) {
    val active by vm.activeThread.collectAsStateWithLifecycle()
    val workspace by vm.activeWorkspace.collectAsStateWithLifecycle()
    val threadProvider by vm.activeModelProvider.collectAsStateWithLifecycle()
    val agentWorkspace by vm.agentDraftWorkspace.collectAsStateWithLifecycle()
    val owner = if (active == null && agentWorkspace == workspace) ThreadComposerDrafts.agentKey(workspace)
        else ThreadComposerDrafts.key(active, workspace)
    val drafts by vm.composerDrafts.collectEntries(owner)
    val draft = drafts[owner] ?: ComposerDraft()
    val options = draft.options
    val providerData by vm.data.collectEntries("providers")
    val draftProvider = ComposerProviderScope.provider(options.modelProvider)
    val currentProvider = if (active != null) ComposerProviderScope.provider(threadProvider)
        else draftProvider ?: ComposerProviderScope.provider(providerData["providers"]?.optString("defaultProviderId"))
    var targetProvider by remember(owner) { mutableStateOf<String?>(null) }
    var choosingProvider by remember(owner) { mutableStateOf(false) }
    var confirmModel by remember(owner) { mutableStateOf<String?>(null) }
    val scopedProvider = targetProvider ?: if (active != null) currentProvider else draftProvider
    val catalogKey = ComposerProviderScope.catalogKey(scopedProvider)
    val models by vm.data.collectEntries(catalogKey)
    val running by vm.running.collectAsStateWithLifecycle()
    val creating by vm.firstThreadStarting.collectAsStateWithLifecycle()
    val switching by vm.threadSwitching.collectAsStateWithLifecycle()
    val restarting by vm.connectionRestarter.busy.collectAsStateWithLifecycle()
    val transfer by AppDataTransfer.state.collectProjection { it.busy }
    val loading by vm.loading.collectProjection { catalogKey in it }
    val errors by vm.errors.collectEntries(catalogKey)
    val engineStatus by vm.bridge.status.collectAsStateWithLifecycle()
    val connected = engineStatus == EngineStatus.Connected
    LaunchedEffect(engineStatus, owner, scopedProvider, providerData["providers"]?.optString("defaultProviderId")) {
        if (connected) {
            vm.ensureCatalog("providers", "modelProvider/list")
            vm.ensureCatalog(catalogKey, "model/list", ComposerProviderScope.request(scopedProvider))
        }
    }
    val available = remember(models[catalogKey]) { records(models[catalogKey]) }
    val providers = records(providerData["providers"]).map {
        CodexChoice(it.optString("id"), PresentationPolicy.providerName(it.optString("name")))
    }.filter { it.id.isNotBlank() }
    val providerName = providers.firstOrNull { it.id == (scopedProvider ?: currentProvider) }?.title
        ?: (scopedProvider ?: currentProvider ?: "默认服务商")
    val selectable = connected && ComposerOptionAccess.canChange(active != null, running,
        targetProvider == null, creating, switching, restarting, transfer) &&
        (active == null || currentProvider != null)
    val catalogReady = selectable && models[catalogKey] != null
    fun reload() {
        if (connected) vm.refresh(catalogKey, "model/list", ComposerProviderScope.request(scopedProvider, true))
        else vm.connect()
    }
    if (choosingProvider) {
        CodexChoiceSheet(title = if (active == null) "选择服务商" else "切换服务商继续对话",
            description = if (active == null) "选择已配置的服务商，再选择模型。"
                else "跨服务商会创建保留历史的新会话，原会话与草稿保留。",
            anchored = anchored, choices = (if (active == null) listOf(CodexChoice("", "遵循默认服务商")) else emptyList()) + providers,
            selectedId = scopedProvider ?: currentProvider, enabled = selectable && !running,
            onSelect = { id ->
                if (active == null) vm.chooseComposerProvider(id)
                else targetProvider = id.takeIf { it != currentProvider }
                choosingProvider = false
            }, onDismiss = { choosingProvider = false })
        return
    }
    val candidate = confirmModel
    val destination = targetProvider
    if (candidate != null && destination != null && active != null) {
        val source = requireNotNull(active)
        CodexConfirmDialog(title = "使用 $candidate 继续对话？",
            message = "将在 $providerName 下创建包含既有对话历史的新会话，后续请求可能将历史内容发送给该服务商。原会话和未发送草稿继续保留。",
            confirmLabel = "切换并继续", confirmEnabled = catalogReady && !running,
            onConfirm = { confirmModel = null; vm.conversationActions.fork(source, modelProvider = destination, model = candidate); onDismiss() },
            onDismiss = { confirmModel = null })
        return
    }
    val selected = if (destination == null) options.model else ""
    val choices = remember(available, options.recentModels, scopedProvider, currentProvider, draft.images.isNotEmpty()) {
        val provider = scopedProvider ?: currentProvider
        available.filter { !it.optBoolean("hidden") }.map { item ->
            val id = item.optString("id").ifBlank { item.optString("model") }
            val name = item.optString("displayName").ifBlank { id }
            val kinds = ComposerModelPolicy.inputKinds(item)
            val detail = listOfNotNull(
                "最近使用".takeIf { ComposerModelPolicy.recentRank(options, provider, id) != Int.MAX_VALUE },
                "服务商默认".takeIf { item.optBoolean("isDefault") },
                when {
                    "image" in kinds && "audio" in kinds -> if (provider == "openai") "图片 · 音频 · 视频抽帧+音轨" else "可尝试图片、音频与视频采样"
                    "image" in kinds -> if (provider == "openai") "支持图片 · 无声视频抽帧" else "可尝试图片 · 无声视频抽帧"
                    "audio" in kinds -> "音频"
                    kinds == listOf("text") && draft.images.isNotEmpty() -> "仅文本 · 当前草稿含媒体"
                    kinds == listOf("text") -> "仅文本"
                    else -> null
                },
                "可调思考强度".takeIf { ComposerModelPolicy.efforts(available, id).isNotEmpty() },
                id.takeIf { it != name }).joinToString(" · ")
            val group = when {
                ComposerModelPolicy.recentRank(options, provider, id) != Int.MAX_VALUE -> "最近使用"
                "image" in kinds && "audio" in kinds -> "图片和音频"
                "image" in kinds -> if (provider == "openai") "支持图片" else "可发送图片"
                "audio" in kinds -> "可发送音频"
                "text" in kinds -> "文字"
                else -> "其他能力"
            }
            CodexChoice(id, name, detail.ifBlank { item.optString("description") }, group)
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
            .sortedBy { ComposerModelPolicy.recentRank(options, provider, it.id) }
    }
    val efforts = remember(available, selected) { ComposerModelPolicy.efforts(available, selected) }
    CodexChoiceSheet(
        title = if (destination != null) "选择 $providerName 模型" else "切换模型",
        anchored = anchored,
        description = when {
            !connected -> "连接尚未恢复，可查看已有选择。"
            running -> "当前任务保持运行；此处仅设置下一次普通发送，已排队任务不变。"
            loading && !catalogReady -> "正在读取可用模型…"
            errors[catalogKey] != null -> "目录更新失败，可继续使用已选模型或重新读取；实际调用由服务商确认。"
            destination != null -> "选择模型后确认跨服务商交接。"
            else -> "仅影响后续任务 · $providerName"
        },
        choices = (if (destination == null) listOf(CodexChoice("", if (active == null) "使用默认模型" else "沿用会话模型")) else emptyList()) + choices,
        selectedId = selected, enabled = selectable && (destination == null || catalogReady),
        recoveryLabel = if (!connected) "重新连接" else if (errors[catalogKey] != null || (!loading && choices.isEmpty())) "重新读取" else null,
        onRecovery = { reload() },
        recoveryMessage = if (!connected) "连接恢复后可以选择模型。" else "可重新获取当前服务商目录。",
        loading = loading,
        wide = true,
        extraContent = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    AtlasSettingsActionRow("服务商", providerName, CodexGlyph.Dns,
                        onClick = { choosingProvider = true }, enabled = selectable && !running && providers.isNotEmpty(),
                        outlined = false)
                }
                if (connected && errors[catalogKey] == null && (loading || choices.isNotEmpty())) {
                    CodexRefreshIcon(loading, { reload() }, "刷新模型目录")
                }
            }
            if (destination == null) Column(Modifier.fillMaxWidth().testTag("composer-effort"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("思考强度", style = MaterialTheme.typography.titleSmall)
                if (efforts.isEmpty()) Text(if (selected.isBlank()) "沿用会话或默认强度" else "此模型未提供可选强度",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                else (listOf("" to "默认") + efforts.map { it to PresentationPolicy.reasoningLabel(it) }).chunked(3).forEach { group ->
                    AtlasCompactChoice(options.effort, group, onSelect = { vm.chooseComposerEffort(it) },
                        enabled = catalogReady, tagPrefix = "composer-effort-")
                }
            }
            if (destination != null) CodexTextAction(onClick = { targetProvider = null }) { Text("返回当前服务商") }
            else CodexTextAction(onClick = onDismiss) { Text("完成") }
        },
        onSelect = { id -> if (destination == null) vm.chooseComposerModel(id) else confirmModel = id },
        onDismiss = onDismiss
    )
}

@Composable
fun ModeChoiceSurface(vm: CodexViewModel, anchored: Boolean = false, onDismiss: () -> Unit) {
    val selected by vm.selectedMode.collectAsStateWithLifecycle()
    val data by vm.data.collectEntries("modes")
    val running by vm.running.collectAsStateWithLifecycle()
    val creating by vm.firstThreadStarting.collectAsStateWithLifecycle()
    val switching by vm.threadSwitching.collectAsStateWithLifecycle()
    val busy = running || creating || switching
    val loading by vm.loading.collectProjection { it.intersect(setOf("modes")) }
    val errors by vm.errors.collectEntries("modes")
    val status by vm.bridge.status.collectAsStateWithLifecycle()
    LaunchedEffect(status) {
        if (status == EngineStatus.Connected)
            vm.refresh("modes", "collaborationMode/list")
    }
    val planSupported = records(data["modes"]).any { it.optString("mode") == "plan" }
    val connected = status == EngineStatus.Connected
    val refreshing = "modes" in loading
    val choices = buildList {
        add(CodexChoice("default", "直接执行", "按照要求完成任务，过程中展示进度"))
        if (planSupported) add(CodexChoice("plan", "先制定计划", "先形成方案，确认后再执行"))
    }
    val description = when {
        busy -> "当前任务正在执行，结束或停止后才能切换。"
        !connected -> "运行核心尚未连接，当前仅显示默认方式。"
        refreshing -> "正在检查内核支持的协作方式。"
        errors["modes"] != null -> "读取模式失败，请重新查询。"
        else -> "仅显示当前内核支持的选项，更改从下一轮生效。"
    }
    val content: @Composable ColumnScope.() -> Unit = {
        AtlasCompactChoice(selected, choices.map { it.id to it.title },
            enabled = connected && !busy && !refreshing,
            onSelect = { id ->
                if (ModeSelectionPolicy.maySelect(id, planSupported, busy, refreshing)) {
                    vm.chooseComposerMode(id)
                    onDismiss()
                }
            }, tagPrefix = "composer-mode-")
        if (refreshing) AtlasOperationProgress("正在读取协作方式")
        if (!connected || errors["modes"] != null || !planSupported) {
            CodexTextAction(onClick = {
                if (connected) vm.refresh("modes", "collaborationMode/list") else vm.connect()
            }) { Text(if (connected) "重新检查" else "重新连接") }
        }
    }
    if (anchored) CodexFloatingMenu("选择协作方式", onDismiss, description, wide = true, content = content)
    else CodexDetailSheet("选择协作方式", onDismiss, subtitle = description, content = content)
}

@Composable
fun RequiresThreadPanel(
    title: String, onOpenThreads: () -> Unit
) {
    CodexEmptyState(
        title = "先选择任务会话",
        description = "“$title”需要关联具体会话。选择历史会话后即可继续操作。",
        actionLabel = "选择会话", onAction = onOpenThreads
    )
}

/** 原有页面可继续通过旧路由打开；新入口统一弹出此详情。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutInfoSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val status by vm.bridge.status.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    CodexDetailSheet(title = "关于应用", onDismiss = onDismiss) {
        Text("版本 v$version · Android 29+", style = MaterialTheme.typography.titleMedium)
        StatusPill(if (status == EngineStatus.Connected) "本机内核已连接" else "内核需要检查",
            good = status == EngineStatus.Connected)
        DividerLine()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("项目来源：sy220284/111", color = Ink.muted, modifier = Modifier.weight(1f))
            CodexTextAction(onClick = {
                clipboard.setText(AnnotatedString("111 Android v$version · sy220284/111 · Codex app-server"))
                copied = true
            }, modifier = Modifier.testTag("about-copy-version")) {
                Text(if (copied) "已复制" else "复制版本信息")
            }
        }
        Text("运行核心：Codex app-server（本地进程）", color = Ink.muted)
        Text("工程文件和配置保存在 Android 应用私有目录；登录信息由运行核心管理。",
            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        Text("上游 Codex：Apache-2.0；增量更新组件：HDiffPatch（MIT）。",
            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
    }
}
