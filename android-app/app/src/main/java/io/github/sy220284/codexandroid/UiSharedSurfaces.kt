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
import org.json.JSONObject

/**
 * V5 公共呈现：选择=Sheet、长工作=Page、危险操作=ConfirmDialog、
 * 状态=Sheet、附件=独立 Sheet；调用和结果复用已有真实 ViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineStatusSheet(
    vm: CodexViewModel, onDismiss: () -> Unit, navigate: (String) -> Unit
) {
    val status by vm.bridge.status.collectAsState()
    val terminalRunning by vm.terminalSession.collectProjection { it.running }
    CodexDetailSheet(title = "运行状态", onDismiss = onDismiss) {
        val label = when (status) {
            EngineStatus.Connected -> "本机内核已连接"
            EngineStatus.Starting -> "内核正在启动"
            is EngineStatus.Unavailable -> "内核连接异常"
        }
        StatusPill(label, tone = if (status == EngineStatus.Connected) StatusTone.Success else StatusTone.Warning)
        if (status is EngineStatus.Unavailable) {
            AtlasFieldFeedback(PresentationPolicy.readableError((status as EngineStatus.Unavailable).reason), isError = true)
        } else if (status == EngineStatus.Starting) {
            AtlasOperationProgress("正在连接本机运行核心")
        }
        if (status != EngineStatus.Connected) {
            AtlasSettingsActionRow("重新连接本机内核", "保持当前会话和草稿", CodexGlyph.Retry,
                onClick = { onDismiss(); vm.connectionRestarter.reconnect() },
                enabled = status != EngineStatus.Starting, outlined = false)
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
    val models by vm.data.collectEntries("models")
    val selected by vm.modelId.collectAsState()
    val active by vm.activeThread.collectAsState()
    val drafts by vm.composerDrafts.collectEntries(ThreadComposerDrafts.key(active))
    val running by vm.running.collectAsState()
    val creating by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val effort = drafts[ThreadComposerDrafts.key(active)]?.options?.effort.orEmpty()
    var selectingEffort by remember { mutableStateOf(false) }
    val loading by vm.loading.collectProjection { it.intersect(setOf("models")) }
    val errors by vm.errors.collectEntries("models")
    val engineStatus by vm.bridge.status.collectAsState()
    LaunchedEffect(engineStatus) {
        if (engineStatus == EngineStatus.Connected) vm.refresh("models", "model/list")
    }
    val modelData = models["models"]
    val available = remember(modelData) { records(modelData) }
    val choices = remember(available) {
        available.filter { !it.optBoolean("hidden") }.map { item ->
            val id = item.optString("id").ifBlank { item.optString("model") }
            CodexChoice(id, item.optString("displayName").ifBlank { id })
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    }
    val selectable = engineStatus == EngineStatus.Connected && "models" !in loading && !running && !creating && !switching
    val efforts = remember(available, selected) { ComposerModelPolicy.efforts(available, selected) }
    if (selectingEffort) {
        val options = listOf("") + efforts
        val actual = options.indexOf(effort).coerceAtLeast(0)
        var preview by remember(actual, options) { mutableIntStateOf(actual) }
        val content: @Composable ColumnScope.() -> Unit = {
            Text(if (options[preview].isBlank()) "遵循默认强度" else PresentationPolicy.reasoningLabel(options[preview]),
                color = Ink.copper, style = MaterialTheme.typography.titleMedium)
            if (options.size > 1) CodexDiscreteSlider(preview, options.size, selectable,
                onPreview = { preview = it }, onFinished = {
                    if (selectable) vm.chooseComposerEffort(options[preview])
                    selectingEffort = false
                }, modifier = Modifier.testTag("composer-effort-slider"))
            Text("松手后应用到后续任务", color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (anchored) CodexFloatingMenu("思考强度", { selectingEffort = false }, wide = true, content = content)
        else CodexDetailSheet("思考强度", { selectingEffort = false }, content = content)
        return
    }
    CodexChoiceSheet(
        title = "选择运行模型", anchored = anchored,
        description = when {
            engineStatus != EngineStatus.Connected ->
                "本机内核尚未连接。可查看已保存的模型选择；连接内核后会自动刷新可用目录。"
            "models" in loading -> "正在读取内核可用模型…"
            errors["models"] != null -> "读取模型目录失败：${errors["models"]}。可手动重新加载。"
            else -> "更改只影响后续任务；已运行任务沿用当前模型。"
        },
        choices = listOf(CodexChoice("", "遵循默认模型", "使用当前服务的默认选择")) + choices,
        selectedId = selected,
        enabled = selectable,
        recoveryLabel = if (engineStatus != EngineStatus.Connected) "重新连接"
            else if (errors["models"] != null || choices.isEmpty()) "重新读取" else null,
        onRecovery = {
            if (engineStatus == EngineStatus.Connected) vm.refresh("models", "model/list")
            else vm.connect()
        },
        recoveryMessage = when {
            engineStatus != EngineStatus.Connected -> "运行核心未连接，恢复后才可更改模型。"
            errors["models"] != null -> "模型目录读取失败，可重试。"
            choices.isEmpty() -> "当前没有可用模型，检查服务状态后刷新。"
            else -> "可重新读取模型目录，已选择的模型不会因此改变。"
        },
        searchable = true,
        loading = "models" in loading,
        extraContent = {
            AtlasSettingsActionRow("思考强度",
                if (selected.isBlank()) "先选择具体模型" else if (efforts.isEmpty()) "该模型未提供可选强度"
                else if (effort.isBlank()) "遵循默认强度" else PresentationPolicy.reasoningLabel(effort),
                CodexGlyph.Speed, onClick = { selectingEffort = true },
                enabled = selectable && efforts.isNotEmpty(), outlined = false,
                modifier = Modifier.testTag("composer-effort"))
        },
        onSelect = { id -> vm.chooseComposerModel(id) },
        onDismiss = onDismiss
    )
}

@Composable
fun ModeChoiceSurface(vm: CodexViewModel, anchored: Boolean = false, onDismiss: () -> Unit) {
    val selected by vm.selectedMode.collectAsState()
    val data by vm.data.collectEntries("modes")
    val running by vm.running.collectAsState()
    val creating by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val busy = running || creating || switching
    val loading by vm.loading.collectProjection { it.intersect(setOf("modes")) }
    val errors by vm.errors.collectEntries("modes")
    val status by vm.bridge.status.collectAsState()
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
    CodexChoiceSheet(
        title = "选择协作方式", description = description, anchored = anchored,
        choices = choices, selectedId = selected,
        enabled = connected && !busy && !refreshing,
        recoveryLabel = if (!connected) "重新连接" else if (errors["modes"] != null || !planSupported) "重新检查" else null,
        onRecovery = {
            if (connected) vm.refresh("modes", "collaborationMode/list") else vm.connect()
        },
        recoveryMessage = if (connected) "可重新查询当前内核支持的工作方式。"
            else "运行核心未连接，恢复连接后再选择工作方式。",
        loading = refreshing,
        onSelect = { id ->
            if (ModeSelectionPolicy.maySelect(id, planSupported, busy, refreshing)) {
                vm.chooseComposerMode(id)
                onDismiss()
            }
        }, onDismiss = onDismiss
    )
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
    val status by vm.bridge.status.collectAsState()
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
