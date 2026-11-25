package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun EngineDiagnosticsPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsState()
    val errors by vm.errors.collectAsState()
    val data by vm.data.collectEntries("diagnostics")
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticResult by rememberSaveable { mutableStateOf(false) }
    val stderr by vm.bridge.recentStderr.collectAsState()
    val coreIdentity by vm.bridge.runtimeUserAgent.collectAsState()
    val protection by CodexForegroundService.protectionState.collectAsState()
    val checking by vm.loading.collectProjection { "diagnostics" in it }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("diagnostics") == "logs") showLogs = true
    }
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val retirement by vm.bridge.retirementBusy.collectAsState()
    val retry by vm.recoveryDelayMs.collectAsState()
    val work = observeGlobalWorkSnapshot(vm)
    var showCrashes by remember { mutableStateOf(false) }
    var showCrashReader by remember { mutableStateOf(false) }
    var confirmClearCrashes by remember { mutableStateOf(false) }
    var crashRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val connected = status == EngineStatus.Connected && !retirement && !restarting
    val canReconnect = RuntimeStatusPresentation.canReconnect(status, retirement, restarting) &&
        !work.dataTransferBusy
    val canRestart = !retirement && !restarting && status != EngineStatus.Starting &&
        ConnectionRestartPolicy.canRestart(work)
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val processArch = remember { System.getProperty("os.arch").orEmpty() }
    ScreenColumn {
        Row(Modifier.fillMaxWidth().testTag("diagnostics-overview"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape,
                color = Ink.elevated, border = BorderStroke(1.dp, Ink.copperDim)) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    CodexIcon(CodexGlyph.Memory, null, Modifier.size(32.dp), tint = Ink.copper)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本机引擎", style = MaterialTheme.typography.headlineSmall, color = Ink.parchment)
                Text(RuntimeStatusPresentation.label(status, retirement, restarting, retry),
                    color = if (connected) Ink.jade else Ink.warning,
                    style = MaterialTheme.typography.bodyMedium)
                Text("原生 Codex · stdio", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(RuntimeStatusPresentation.detail(status, retirement, restarting, retry),
            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        CodexAdaptiveActions(
            first = { modifier ->
                CodexSecondaryButton(
                    onClick = { vm.connectionRestarter.reconnect() },
                    enabled = canReconnect,
                    modifier = modifier.testTag("diagnostics-connect")
                ) {
                    CodexIcon(CodexGlyph.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("连接")
                }
            },
            second = { modifier ->
                CodexPrimaryButton(
                    onClick = { vm.connectionRestarter.restart() },
                    enabled = canRestart,
                    busy = restarting,
                    modifier = modifier.testTag("diagnostics-restart")
                        .explainDisabled(!canRestart, work.blockReason.ifBlank { "等待内核状态稳定" })
                ) {
                    CodexIcon(CodexGlyph.Retry, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重启连接")
                }
            }
        )
        if (!work.canDisruptCoreOrData && !restarting) {
            Text(work.blockReason, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("运行策略", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-runtime-policy")) {
            RuntimeInfoRow("打开应用时连接", "授权工作区后自动初始化")
            DividerLine()
            RuntimeInfoRow("异常恢复", "可恢复故障自动重连，保留历史与草稿")
            DividerLine()
            RuntimeInfoRow("后台空闲释放", "无任务时后台空闲 90 秒释放内核")
        }
        RuntimeLogPanel(stderr, onRead = { showLogs = true })
        if (showLogs) {
            if (stderr.isNotEmpty()) FullTextReaderDialog("启动日志", stderr.joinToString("\n")) {
                showLogs = false
            } else CodexInfoDialog("启动日志", onDismiss = { showLogs = false }) {
                CodexEmptyState("暂无启动日志", "内核输出记录后可在这里查看。")
            }
        }
        Text("更多", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-protection")) {
            val protectionLabel = when (protection) {
                ForegroundStartState.Ready -> "系统前台服务已就绪"
                ForegroundStartState.Starting -> "正在申请系统后台保护"
                is ForegroundStartState.Failed -> "后台保护失败，请保持应用在前台"
                ForegroundStartState.Stopped -> if (work.requiresForegroundProtection)
                    "当前工作需要后台保护，等待系统启动" else "有任务时自动启用后台保护"
            }
            RuntimeInfoRow("后台运行", protectionLabel)
            errors["foregroundProtection"]?.let { ErrorText(it) }
            DividerLine()
            AtlasSettingsActionRow("检查运行状况",
                if (checking) "正在读取内核诊断…" else "读取官方诊断结果",
                CodexGlyph.BugReport,
                onClick = { vm.refresh("diagnostics", "server/diagnostics") },
                enabled = connected && !checking, outlined = false,
                modifier = Modifier.testTag("diagnostics-refresh"))
            if (checking) AtlasOperationProgress("正在读取内核诊断")
            if (connected) {
                PresentationPolicy.visibleErrors(errors, listOf("diagnostics", "engine"))
                    .forEach { ErrorText(it) }
            } else errors["diagnostics"]?.let { ErrorText(it) }
            data["diagnostics"]?.takeIf { connected }?.let { result ->
                AtlasSettingsActionRow("查看诊断结果", "查看官方接口返回的完整数据",
                    CodexGlyph.Description, onClick = { showDiagnosticResult = true }, outlined = false)
                if (showDiagnosticResult) FullTextReaderDialog("诊断结果", result.toString(2)) {
                    showDiagnosticResult = false
                }
            }
        }
        Text("运行环境", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-environment")) {
            RuntimeInfoRow("应用版本", appVersion.ifBlank { "未读取" })
            DividerLine()
            RuntimeInfoRow("内核标识", coreIdentity?.takeIf { connected } ?: "等待内核握手回执")
            DividerLine()
            RuntimeInfoRow("进程架构", processArch.ifBlank { "未读取" })
            DividerLine()
            RuntimeInfoRow("通信方式", "stdio · JSON-RPC")
        }
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-crash-history")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本机崩溃记录", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                CodexDetailTrigger("查看记录", onClick = { showCrashes = true })
            }
            Text("仅保存异常类型、时间和代码调用位置；不会上传执行内容。",
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            if (showCrashes) CodexDetailSheet(
                title = "本机崩溃记录", startExpanded = true,
                onDismiss = { showCrashes = false }
            ) {
                val stored = remember(showCrashes, crashRevision) {
                    LocalCrashReporter.read(context)
                }
                if (stored.isBlank()) {
                    Text("尚无本机崩溃记录", color = Ink.muted)
                } else {
                    AtlasSettingsActionRow("搜索和复制完整日志", "在独立阅读器中查看", CodexGlyph.Description,
                        onClick = { showCrashReader = true }, outlined = false)
                    if (showCrashReader) FullTextReaderDialog("本机崩溃记录", stored) {
                        showCrashReader = false
                    }
                    CodexTextAction(onClick = { confirmClearCrashes = true }) {
                        Text("清除本机崩溃记录", color = Ink.red)
                    }
                    if (confirmClearCrashes) CodexConfirmDialog(
                        title = "清除本机崩溃记录？", message = "已保存的诊断记录将被删除。",
                        confirmLabel = "确认清除", destructive = true,
                        onConfirm = {
                            LocalCrashReporter.clear(context)
                            crashRevision++
                            confirmClearCrashes = false
                        }, onDismiss = { confirmClearCrashes = false })
                }
            }
        }
        if (status is EngineStatus.Unavailable) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("连接排查", style = MaterialTheme.typography.titleMedium)
                listOf("重新连接运行服务", "核实账号登录与失败功能", "根据上方日志定位启动异常").forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text((index + 1).toString(), color = Ink.copper, style = MaterialTheme.typography.titleMedium)
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeInfoRow(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.parchment)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}

/** A bounded preview uses the same sanitized snapshot as the full reader and copy action. */
@Composable
private fun RuntimeLogPanel(lines: List<String>, onRead: () -> Unit) {
    val copy = rememberConversationCopy()
    val context = LocalContext.current
    val logText = remember(lines) { lines.joinToString("\n") }
    Surface(Modifier.fillMaxWidth().testTag("diagnostics-log-panel"),
        shape = RoundedCornerShape(22.dp), color = RuntimeConsolePalette.background,
        contentColor = RuntimeConsolePalette.foreground) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("日志", style = MaterialTheme.typography.titleMedium)
                    Text("最近 ${lines.size} 行", style = MaterialTheme.typography.labelMedium,
                        color = RuntimeConsolePalette.secondary)
                }
                CodexIconAction(onClick = { copy(logText, CopyContentFormat.Literal) },
                    enabled = lines.isNotEmpty(), modifier = Modifier.testTag("diagnostics-log-copy")) {
                    CodexIcon(CodexGlyph.ContentCopy, "复制启动日志",
                        tint = if (lines.isNotEmpty()) RuntimeConsolePalette.foreground else RuntimeConsolePalette.secondary)
                }
                CodexIconAction(onClick = {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, logText)
                        context.startActivity(android.content.Intent.createChooser(intent, "分享启动日志"))
                    } catch (failure: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, "系统没有可用的分享应用",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = lines.isNotEmpty(), modifier = Modifier.testTag("diagnostics-log-share")) {
                    CodexIcon(CodexGlyph.Share, "分享启动日志",
                        tint = if (lines.isNotEmpty()) RuntimeConsolePalette.foreground else RuntimeConsolePalette.secondary)
                }
            }
            Text(if (lines.isEmpty()) "暂无启动日志" else lines.takeLast(6).joinToString("\n"),
                modifier = Modifier.fillMaxWidth().testTag("diagnostics-log-preview"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = RuntimeConsolePalette.foreground, maxLines = 10, overflow = TextOverflow.Ellipsis)
            CodexTextAction(onClick = onRead, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = RuntimeConsolePalette.foreground)) {
                Text("查看启动日志")
                Spacer(Modifier.width(6.dp))
                CodexIcon(CodexGlyph.ChevronRight, null)
            }
        }
    }
}

