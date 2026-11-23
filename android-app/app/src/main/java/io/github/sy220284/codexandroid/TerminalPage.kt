package io.github.sy220284.codexandroid

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun TerminalPage(vm: CodexViewModel, embedded: Boolean = false) {
    var command by rememberSaveable { mutableStateOf("") }
    var interactive by rememberSaveable { mutableStateOf(false) }
    var ttyRows by rememberSaveable { mutableStateOf("24") }
    var ttyCols by rememberSaveable { mutableStateOf("80") }
    var showTerminalSize by rememberSaveable { mutableStateOf(false) }
    var recentCommands by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var historyPosition by remember { mutableStateOf(TerminalHistoryPosition()) }
    var expandedOutput by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriptAvailable by vm.terminalTranscript.available.collectAsState()
    val transcriptError by vm.terminalTranscript.error.collectAsState()
    var exportTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val logPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            exportBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("无法写入所选文件")
                        out.use { vm.terminalTranscript.export(target.first, target.second, it) }
                    }
                    exportStatus = "终端日志已保存到所选位置"
                } catch (failure: Exception) {
                    exportStatus = "终端日志保存失败：" + (failure.message ?: "请检查保存位置")
                } finally {
                    exportBusy = false
                }
            }
        }
    }
    var confirm by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val session by vm.terminalSession.collectAsState()
    val terminalStarting by vm.terminalStarting.collectAsState()
    val errors by vm.errors.collectAsState()
    var historicalStdout by remember(session.processId) { mutableStateOf("") }
    var historicalOffset by remember(session.processId) { mutableStateOf<Long?>(null) }
    var historicalMore by remember(session.processId) { mutableStateOf(true) }
    var historicalLoading by remember(session.processId) { mutableStateOf(false) }
    var historicalError by remember(session.processId) { mutableStateOf("") }
    val activeWorkspace by vm.activeWorkspace.collectAsState()
    val consoleScroll = rememberScrollState()
    var followConsoleTail by remember(session.processId) { mutableStateOf(true) }
    LaunchedEffect(consoleScroll) {
        snapshotFlow { consoleScroll.isScrollInProgress to
            (consoleScroll.value >= consoleScroll.maxValue - 24) }.collect { (scrolling, atEnd) ->
            if (scrolling) followConsoleTail = atEnd
        }
    }
    LaunchedEffect(consoleScroll, session.processId) {
        snapshotFlow { consoleScroll.maxValue }.collect { bottom ->
            if (followConsoleTail) consoleScroll.scrollTo(bottom)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!embedded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地终端", style = MaterialTheme.typography.titleLarge)
                    Text("工作区 · " + File(activeWorkspace).relativeTo(
                        File(vm.workingDirectory)).path.takeIf { it != "." }
                        ?.let { "projects/" + it }.orEmpty().ifBlank { "projects/" },
                        color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Ink.muted)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).testTag("terminal-console")) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val consoleHeight = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(consoleScroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (session.processId.isNotEmpty()) {
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (session.running) Icons.Outlined.Terminal
                                else if (session.error != null || session.exitCode != 0)
                                    Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                                null, tint = if (session.running || session.exitCode == 0) Ink.jade else Ink.red
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (session.terminating) "正在停止"
                                    else if (session.running) "命令执行中"
                                    else if (session.error != null) "命令执行失败"
                                    else "执行结束 · 返回码 " + (session.exitCode?.toString() ?: "未知"),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(session.command, color = Ink.muted,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(
                                if (session.running) "运行中" else if (session.exitCode == 0) "完成" else "异常",
                                !session.running && session.exitCode == 0,
                                tone = if (session.running) StatusTone.Info else if (session.exitCode == 0) StatusTone.Success else StatusTone.Danger
                            )
                        }
                        if (session.running) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (session.tty) {
                                TextButton(onClick = { showTerminalSize = !showTerminalSize },
                                    modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(if (showTerminalSize) "收起终端尺寸" else "调整终端尺寸")
                                }
                                if (showTerminalSize) {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(ttyRows, { ttyRows = it.filter(Char::isDigit).take(3) },
                                        label = { Text("行数") }, singleLine = true,
                                        modifier = Modifier.weight(1f), enabled = !session.terminating)
                                    OutlinedTextField(ttyCols, { ttyCols = it.filter(Char::isDigit).take(3) },
                                        label = { Text("列数") }, singleLine = true,
                                        modifier = Modifier.weight(1f), enabled = !session.terminating)
                                }
                                OutlinedButton(onClick = {
                                    vm.resizeTerminal(ttyRows.toIntOrNull() ?: 0,
                                        ttyCols.toIntOrNull() ?: 0)
                                }, enabled = !session.terminating &&
                                    ttyRows.toIntOrNull() in 2..300 &&
                                    ttyCols.toIntOrNull() in 10..400) {
                                    Text("应用尺寸")
                                }
                                errors["terminalResize"]?.let { ErrorText(it) }
                                }
                            }
                        }
                        session.error?.let { ErrorText(it) }
                        if (session.outputTruncated) {
                            CodexNotice(
                                if (transcriptAvailable) "正在查看最近 128KiB；可按需加载较早输出，或导出完整本机日志。"
                                else "界面仅保留最近 128KiB，较早日志已不可读取。",
                                NoticeTone.Warning
                            )
                        }
                        transcriptError?.takeIf(String::isNotBlank)?.let { ErrorText(it) }
                    }
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("标准输出", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(session.stdoutText))
                                    vm.actionFeedback.tryEmit(ActionFeedbackReceipt("终端输出已复制"))
                                },
                                enabled = session.stdoutText.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, null,
                                    modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("复制")
                            }
                            TextButton(
                                onClick = { expandedOutput = !expandedOutput },
                                enabled = session.stdoutText.length > 16_000,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(if (expandedOutput) "收起" else "展开") }
                        }
                        if (transcriptAvailable && session.outputTruncated && historicalMore) {
                            OutlinedButton(
                                onClick = {
                                    val targetId = session.processId
                                    historicalLoading = true
                                    historicalError = ""
                                    scope.launch {
                                        try {
                                            val page = vm.terminalTranscript.readStdoutBefore(
                                                targetId, historicalOffset)
                                            if (vm.terminalSession.value.processId == targetId) {
                                                historicalOffset = page.beforeOffset
                                                historicalMore = page.hasMore
                                                historicalStdout = (page.text + historicalStdout).takeLast(256_000)
                                            }
                                        } catch (failure: Exception) {
                                            historicalError = failure.message ?: "无法读取更早的输出"
                                        } finally {
                                            historicalLoading = false
                                        }
                                    }
                                },
                                enabled = !historicalLoading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("terminal-load-older")
                            ) {
                                Icon(Icons.Outlined.History, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (historicalLoading) "正在读取…" else "加载更早输出（每次约 32KiB）")
                            }
                        }
                        if (historicalError.isNotBlank()) ErrorText(historicalError)
                        if (historicalStdout.isNotBlank()) {
                            Text("较早的标准输出（本机分页读取）",
                                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                            SelectionContainer {
                                Text(historicalStdout, color = Ink.parchment,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("terminal-older-stdout"))
                            }
                        }
                        if (transcriptAvailable) {
                            OutlinedButton(
                                onClick = {
                                    exportTarget = session.processId to session.command
                                    exportStatus = ""
                                    logPicker.launch("Codex-终端执行日志.txt")
                                },
                                enabled = !exportBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("terminal-export-log")
                            ) {
                                Icon(Icons.Outlined.SaveAlt, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (exportBusy) "正在保存…" else "导出本机执行日志（含错误输出）")
                            }
                        }
                        if (exportStatus.isNotBlank())
                            Text(exportStatus,
                                color = if (exportStatus.contains("失败")) Ink.red else Ink.jade,
                                style = MaterialTheme.typography.labelMedium)
                        DividerLine()
                        SelectionContainer {
                            Text(session.stdoutText.takeLast(if (expandedOutput) 128_000 else 16_000).ifBlank {
                                if (session.running) "等待命令输出…" else "(无标准输出)"
                            }, fontFamily = FontFamily.Monospace, color = Ink.parchment,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("terminal-stdout"))
                        }
                        if (session.stderrBytes.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("错误输出", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer {
                                Text(session.stderrText.takeLast(16_000),
                                    fontFamily = FontFamily.Monospace, color = Ink.red,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    MaterialPanel(Modifier.fillMaxWidth().heightIn(min = minOf(consoleHeight.value, 172f).dp)
                        .testTag("terminal-console-empty")) {
                        Text("等待执行命令", style = MaterialTheme.typography.titleMedium)
                        DividerLine()
                        SelectionContainer {
                            Text("在下方输入命令，执行输出会显示在这里。",
                                modifier = Modifier.testTag("terminal-stdout"),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                        }
                        Text("常用命令 · 选择后可核对再执行",
                            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("pwd", "ls -la", "git status").forEach { example ->
                                AssistChip(onClick = { command = example },
                                    label = { Text(example, fontFamily = FontFamily.Monospace) })
                            }
                        }
                    }
                }
                }
            }
        }
        if (session.running) {
            MaterialPanel(Modifier.fillMaxWidth().testTag("terminal-active-controls")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (session.terminating) "正在停止" else "命令执行中",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    if (session.tty) StatusPill("交互式终端", false, StatusTone.Info)
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("向运行中的命令输入一行") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    enabled = !session.terminating
                )
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        val submitted = input
                        vm.sendTerminalInput(submitted) {
                            input = ThreadDisplayPolicy.afterAcceptedDraft(input, submitted)
                        }
                    }, enabled = input.isNotEmpty() && !session.terminating,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("发送输入")
                    }
                    OutlinedButton(onClick = { vm.stopTerminalCommand() },
                        enabled = !session.terminating,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red)) {
                        Icon(Icons.Outlined.StopCircle, null)
                        Spacer(Modifier.width(5.dp))
                        Text("停止")
                    }
                }
                errors["terminalInput"]?.let { ErrorText(it) }
                errors["terminalStop"]?.let { ErrorText(it) }
            }
        } else {
            MaterialPanel(Modifier.fillMaxWidth().testTag("terminal-composer-panel")) {
                OutlinedTextField(
                    command,
                    onValueChange = {
                        command = it
                        // Editing an old command starts a fresh history traversal.
                        historyPosition = TerminalHistoryPosition()
                    },
                    label = { Text("输入命令") },
                    placeholder = { Text("pwd && ls -la") },
                    modifier = Modifier.fillMaxWidth()
                        .testTag("terminal-command-input")
                        .onPreviewKeyEvent { event ->
                            if (session.running || event.type != KeyEventType.KeyDown ||
                                '\n' in command) return@onPreviewKeyEvent false
                            val choice = when (event.key) {
                                Key.DirectionUp -> TerminalHistoryNavigation.previous(
                                    command, recentCommands, historyPosition)
                                Key.DirectionDown -> TerminalHistoryNavigation.next(
                                    recentCommands, historyPosition)
                                else -> null
                            } ?: return@onPreviewKeyEvent false
                            command = choice.command
                            historyPosition = choice.position
                            true
                        },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (command.isNotBlank() && !session.running && !terminalStarting) {
                            confirm = true
                        }
                    }),
                    enabled = !session.running
                )
                if (recentCommands.isNotEmpty()) {
                    Text("外接键盘可用 ↑ / ↓ 逐条查看历史命令；多行内容保留正常光标移动。",
                        style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    Box {
                        TextButton(
                            onClick = { historyVisible = true },
                            enabled = !session.running,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.History, null)
                            Spacer(Modifier.width(7.dp))
                            Text("使用历史命令")
                        }
                        DropdownMenu(
                            expanded = historyVisible,
                            onDismissRequest = { historyVisible = false }
                        ) {
                            recentCommands.forEach { previous ->
                                DropdownMenuItem(
                                    text = {
                                        Text(previous, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis)
                                    },
                                    onClick = {
                                        command = previous
                                        historyPosition = TerminalHistoryPosition()
                                        historyVisible = false
                                    }
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("交互式终端", style = MaterialTheme.typography.bodyMedium)
                        Text("需要持续输入或运行交互程序时开启",
                            color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = interactive, onCheckedChange = { interactive = it },
                        enabled = !session.running && !terminalStarting)
                }
                CodexPrimaryButton(onClick = { confirm = true },
                    enabled = command.isNotBlank() && !session.running && !terminalStarting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (terminalStarting) "正在启动后台保护…" else "执行命令")
                }
            }
        }
        errors["terminal"]?.let { ErrorText(it) }
    }
    if (confirm) CodexConfirmDialog(
        title = if (TerminalCommandPolicy.evaluate(command).dangerous) "高风险命令 · 二次确认" else "确认执行命令",
        message = TerminalCommandPolicy.evaluate(command).warning ?: "将由 Codex 在当前工作区执行以下命令。请检查执行内容及目录。",
        detail = command + "\n\n执行位置：" +
            File(activeWorkspace).relativeTo(File(vm.workingDirectory)).path
                .takeIf { it != "." }?.let { "projects/" + it }.orEmpty()
                .ifBlank { "projects/" } +
            if (interactive) "\n交互式 PTY 终端" else "\n流式命令",
        confirmLabel = if (TerminalCommandPolicy.evaluate(command).dangerous) "确认执行高风险命令" else "确认执行",
        destructive = TerminalCommandPolicy.evaluate(command).dangerous,
        onConfirm = {
            val accepted = command.trim()
            if (accepted.isNotBlank()) {
                recentCommands = ArrayList(
                    (listOf(accepted) + recentCommands.filterNot { it == accepted }).take(10)
                )
                historyPosition = TerminalHistoryPosition()
                vm.startTerminalCommand(accepted, tty = interactive)
            }
            confirm = false
        },
        onDismiss = { confirm = false }
    )
}

