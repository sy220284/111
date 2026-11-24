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
    var readerOutput by remember { mutableStateOf<Pair<String, String>?>(null) }
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
    val session by vm.terminalSession.collectProjection { TerminalControlState.from(it) }
    val outputState = vm.terminalSession.collectFrameState(session.processId)
    val renderedOutput = remember(outputState) {
        derivedStateOf { TerminalRenderedOutput.from(outputState.value) }
    }
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
    var automaticConsoleScroll by remember(session.processId) { mutableStateOf(false) }
    LaunchedEffect(consoleScroll, session.processId) {
        consoleScroll.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start)
                followConsoleTail = false
        }
    }
    LaunchedEffect(consoleScroll, session.processId) {
        snapshotFlow { Triple(consoleScroll.isScrollInProgress,
            consoleScroll.value >= consoleScroll.maxValue - 24, automaticConsoleScroll) }
            .collect { (scrolling, atEnd, automatic) ->
                if (!scrolling && atEnd) followConsoleTail = true
                else if (scrolling && !automatic) followConsoleTail = false
            }
    }
    LaunchedEffect(consoleScroll, session.processId) {
        snapshotFlow { consoleScroll.maxValue }.collect {
            if (followConsoleTail) {
                withFrameNanos { }
                if (followConsoleTail && consoleScroll.value < consoleScroll.maxValue) {
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            automaticConsoleScroll = true
                            try {
                                consoleScroll.scrollTo(consoleScroll.maxValue)
                            } finally {
                                automaticConsoleScroll = false
                            }
                        }
                    }
                }
            }
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
                CodexIcon(CodexGlyph.Terminal, contentDescription = null, tint = Ink.muted)
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
                            CodexIcon(
                                if (session.running) CodexGlyph.Terminal
                                else if (session.error != null || session.exitCode != 0)
                                    CodexGlyph.TaskError else CodexGlyph.TaskComplete,
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
                            AtlasOperationProgress("命令正在执行")
                            if (session.tty) {
                                CodexDetailTrigger(
                                    label = "调整终端尺寸",
                                    onClick = { showTerminalSize = true },
                                    modifier = Modifier.testTag("terminal-resize-toggle")
                                )
                                if (showTerminalSize) CodexDetailSheet(
                                    title = "终端窗口尺寸",
                                    subtitle = "调整行数和列数后同步到正在运行的终端",
                                    onDismiss = { showTerminalSize = false }
                                ) {
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CodexInputField(ttyRows, {
                                            ttyRows = it.filter(Char::isDigit).take(3)
                                        }, label = { Text("行数") }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                            isError = ttyRows.isNotBlank() && ttyRows.toIntOrNull() !in 2..300,
                                            modifier = Modifier.weight(1f)
                                                .testTag("terminal-resize-rows"),
                                            enabled = !session.terminating)
                                        CodexInputField(ttyCols, {
                                            ttyCols = it.filter(Char::isDigit).take(3)
                                        }, label = { Text("列数") }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                            isError = ttyCols.isNotBlank() && ttyCols.toIntOrNull() !in 10..400,
                                            modifier = Modifier.weight(1f)
                                                .testTag("terminal-resize-cols"),
                                            enabled = !session.terminating)
                                    }
                                    val validSize = ttyRows.toIntOrNull() in 2..300 &&
                                        ttyCols.toIntOrNull() in 10..400
                                    if (!validSize) AtlasFieldFeedback(
                                        "行数 2–300，列数 10–400", isError = true)
                                    CodexPrimaryButton(
                                        onClick = {
                                            vm.resizeTerminal(ttyRows.toIntOrNull() ?: 0,
                                                ttyCols.toIntOrNull() ?: 0)
                                        },
                                        enabled = !session.terminating && validSize,
                                        modifier = Modifier.fillMaxWidth()
                                            .testTag("terminal-resize-apply")
                                    ) { Text("应用尺寸") }
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
                        TerminalOutputHeader(vm, session.processId, renderedOutput) { text ->
                            readerOutput = "终端标准输出" to text
                        }
                        if (transcriptAvailable && session.outputTruncated && historicalMore) {
                            CodexSecondaryButton(
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
                                CodexIcon(CodexGlyph.History, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (historicalLoading) "正在读取…" else "加载更早输出（每次约 32KiB）")
                            }
                        }
                        if (historicalError.isNotBlank()) ErrorText(historicalError)
                        if (historicalStdout.isNotBlank()) {
                            Text("较早的标准输出（本机分页读取）",
                                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                            CodexTextAction(
                                onClick = { readerOutput = "较早的标准输出" to historicalStdout },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("terminal-older-stdout-open")
                            ) {
                                CodexIcon(CodexGlyph.OpenInFull, null)
                                Spacer(Modifier.width(8.dp))
                                Text("阅读较早输出 · ${historicalStdout.length} 字符")
                            }
                        }
                        if (transcriptAvailable) {
                            CodexSecondaryButton(
                                onClick = {
                                    exportTarget = session.processId to session.command
                                    exportStatus = ""
                                    logPicker.launch("Codex-终端执行日志.txt")
                                },
                                enabled = !exportBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("terminal-export-log")
                            ) {
                                CodexIcon(CodexGlyph.SaveAlt, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (exportBusy) "正在保存…" else "导出本机执行日志（含错误输出）")
                            }
                        }
                        if (exportStatus.isNotBlank())
                            Text(exportStatus,
                                color = if (exportStatus.contains("失败")) Ink.red else Ink.jade,
                                style = MaterialTheme.typography.labelMedium)
                        DividerLine()
                        TerminalOutputPreview(session.processId, session.running, renderedOutput)
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
                                CodexSuggestionChip(onClick = { command = example },
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
                CodexInputField(
                    value = input, onValueChange = { input = it },
                    label = { Text("向运行中的命令输入一行") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    enabled = !session.terminating
                )
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CodexSecondaryButton(onClick = {
                        val submitted = input
                        vm.sendTerminalInput(submitted) {
                            input = ThreadDisplayPolicy.afterAcceptedDraft(input, submitted)
                        }
                    }, enabled = input.isNotEmpty() && !session.terminating,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("发送输入")
                    }
                    CodexSecondaryButton(onClick = { vm.stopTerminalCommand() },
                        enabled = !session.terminating,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red)) {
                        CodexIcon(CodexGlyph.Stop, null)
                        Spacer(Modifier.width(5.dp))
                        Text("停止")
                    }
                }
                errors["terminalInput"]?.let { ErrorText(it) }
                errors["terminalStop"]?.let { ErrorText(it) }
            }
        } else {
            MaterialPanel(Modifier.fillMaxWidth().testTag("terminal-composer-panel")) {
                CodexInputField(
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
                    AtlasTerminalHistoryControls(command, recentCommands, historyPosition,
                        enabled = !session.running && !terminalStarting,
                        onChoice = { choice -> command = choice.command; historyPosition = choice.position },
                        onOpenHistory = { historyVisible = true })
                    if (historyVisible) CodexDetailSheet(
                        title = "历史命令",
                        subtitle = "选择后填入输入栏，执行前仍需确认",
                        onDismiss = { historyVisible = false }
                    ) {
                        recentCommands.forEachIndexed { index, previous ->
                            CodexSecondaryButton(
                                onClick = {
                                    command = previous
                                    historyPosition = TerminalHistoryPosition()
                                    historyVisible = false
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("terminal-history-item-$index")
                            ) {
                                Text(previous, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis)
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
                    WhiteSwitch(checked = interactive, onCheckedChange = { interactive = it },
                        enabled = !session.running && !terminalStarting)
                }
                CodexPrimaryButton(onClick = { confirm = true },
                    enabled = command.isNotBlank() && !session.running && !terminalStarting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    CodexIcon(CodexGlyph.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (terminalStarting) "正在启动后台保护…" else "执行命令")
                }
            }
        }
        errors["terminal"]?.let { ErrorText(it) }
    }
    readerOutput?.let { (title, text) ->
        FullTextReaderDialog(title = title, text = text, onDismiss = { readerOutput = null })
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

