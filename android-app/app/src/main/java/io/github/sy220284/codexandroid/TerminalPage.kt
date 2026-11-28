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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun TerminalPage(vm: CodexViewModel, embedded: Boolean = false, navigate: ((String) -> Unit)? = null) {
    var command by rememberSaveable { mutableStateOf("") }
    var interactive by rememberSaveable { mutableStateOf(false) }
    var allowNetwork by remember { mutableStateOf(false) }
    var ttyRows by rememberSaveable { mutableStateOf("24") }
    var ttyCols by rememberSaveable { mutableStateOf("80") }
    var showTerminalSize by rememberSaveable { mutableStateOf(false) }
    var recentCommands by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var historyPosition by remember { mutableStateOf(TerminalHistoryPosition()) }
    var readerOutput by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriptAvailable by vm.terminalTranscript.available.collectAsStateWithLifecycle()
    val transcriptError by vm.terminalTranscript.error.collectAsStateWithLifecycle()
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
    val renderedOutput = rememberTerminalOutput(outputState, session.processId)
    val terminalStarting by vm.terminalStarting.collectAsStateWithLifecycle()
    val errors by vm.errors.collectEntries("terminal", "terminalInput", "terminalStop", "terminalResize")
    var historicalPages by remember(session.processId) { mutableStateOf<List<String>>(emptyList()) }
    var selectedHistoryPage by remember(session.processId) { mutableIntStateOf(0) }
    val historicalStdout = historicalPages.getOrNull(selectedHistoryPage).orEmpty()
    var historicalOffset by remember(session.processId) { mutableStateOf<Long?>(null) }
    var historicalMore by remember(session.processId) { mutableStateOf(true) }
    var historicalLoading by remember(session.processId) { mutableStateOf(false) }
    var historicalError by remember(session.processId) { mutableStateOf("") }
    val activeWorkspace by vm.activeWorkspace.collectAsStateWithLifecycle()
    val activeThread by vm.activeThread.collectAsStateWithLifecycle()
    val consoleScroll = key(session.processId) { rememberScrollState() }
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
                if (!scrolling && atEnd && !automatic) followConsoleTail = true
                else if (scrolling && !automatic && !automaticConsoleScroll) followConsoleTail = false
            }
    }
    LaunchedEffect(consoleScroll, session.processId) {
        snapshotFlow { consoleScroll.maxValue to renderedOutput.value }.collect {
            if (followConsoleTail) {
                withFrameNanos { }
                if (followConsoleTail && consoleScroll.value < consoleScroll.maxValue) {
                    automaticConsoleScroll = true
                    try {
                        kotlinx.coroutines.coroutineScope {
                            launch { consoleScroll.scrollTo(consoleScroll.maxValue) }
                        }
                    } finally {
                        automaticConsoleScroll = false
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
                    Text((if (session.running) "执行中 · " else "新命令位置 · ") + (if (session.running) session.workspace ?: activeWorkspace else activeWorkspace).let { directory ->
                        File(directory).toRelativeString(File(vm.workingDirectory)).takeIf { it.isNotBlank() && it != "." }
                            ?.let { "projects/" + it } ?: "projects/"
                    },
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
                    WorkSection(Modifier.fillMaxWidth()) {
                        session.workspace?.let { directory ->
                            Text("执行位置 · " + File(directory).toRelativeString(File(vm.workingDirectory))
                                .let { if (it.isBlank() || it == ".") "projects/" else "projects/$it" },
                                style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                        }
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
                                    ActionNumberStepper(ttyRows, { ttyRows = it }, "行数", 2, 300, 1,
                                        enabled = !session.terminating, modifier = Modifier.testTag("terminal-resize-rows"))
                                    ActionNumberStepper(ttyCols, { ttyCols = it }, "列数", 10, 400, 5,
                                        enabled = !session.terminating, modifier = Modifier.testTag("terminal-resize-cols"))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(24 to 80, 30 to 100, 40 to 120).forEach { (rows, cols) ->
                                            AtlasChoiceRow("$rows × $cols", "", ttyRows == rows.toString() && ttyCols == cols.toString(),
                                                enabled = !session.terminating, onClick = { ttyRows = rows.toString(); ttyCols = cols.toString() })
                                        }
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
                    WorkSection(Modifier.fillMaxWidth(), WorkSectionKind.Reading) {
                        TerminalOutputHeader(vm, session.processId, renderedOutput, session.outputTruncated) { text ->
                            readerOutput = "终端标准输出" to text
                        }
                        TerminalOutputPreview(session.processId, session.running, renderedOutput)
                        DividerLine()
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
                                                if (page.text.isNotEmpty()) {
                                                    historicalPages = historicalPages + page.text
                                                    selectedHistoryPage = historicalPages.lastIndex
                                                }
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                CodexTextAction(onClick = { selectedHistoryPage-- }, enabled = selectedHistoryPage > 0) { Text("较新") }
                                Text("历史段 " + (selectedHistoryPage + 1) + " / " + historicalPages.size,
                                    style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                                CodexTextAction(onClick = { selectedHistoryPage++ }, enabled = selectedHistoryPage < historicalPages.lastIndex) { Text("较早") }
                            }
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
                    }
                } else {
                    WorkSection(Modifier.fillMaxWidth().heightIn(min = (consoleHeight.value * .70f).dp)
                        .testTag("terminal-console-empty"), WorkSectionKind.Reading) {
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
                    Box {
                    AtlasTerminalHistoryControls(command, recentCommands, historyPosition,
                        enabled = !session.running && !terminalStarting,
                        onChoice = { choice -> command = choice.command; historyPosition = choice.position },
                        onOpenHistory = { historyVisible = true })
                    if (historyVisible) CodexFloatingMenu(
                        title = "历史命令", wide = true,
                        description = "选择后填入输入栏，执行前仍需确认",
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
                CodexNotice("本机执行 · 完整应用权限。命令可访问工作区外的应用可读写文件。", NoticeTone.Info)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("请求本次命令联网", style = MaterialTheme.typography.bodyMedium)
                        Text("关闭时向核心请求限制联网；系统隔离尚未建立。", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    WhiteSwitch(checked = allowNetwork, onCheckedChange = { allowNetwork = it },
                        enabled = !session.running && !terminalStarting)
                }
                if (navigate != null) CodexTextAction(onClick = {
                    val instruction = if (command.isBlank()) "请协助我检查当前工作区，提出需要执行的命令及原因。"
                        else "请先检查以下命令的目的、风险与执行位置；根据当前任务提出执行建议：\n" + command
                    if (appendTaskInstruction(vm, activeThread, instruction)) navigate("chat")
                }, enabled = !session.running && !terminalStarting, modifier = Modifier.fillMaxWidth().testTag("terminal-agent-assist")) {
                    Text("交给 Codex 检查")
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
        message = (TerminalCommandPolicy.evaluate(command).warning ?: "将由 Codex 在当前工作区执行以下命令。请检查执行内容及目录。") +
            "\n\n" + TerminalSessionContract.ACCESS_NOTICE +
            (if (interactive) " 交互会话后续输入同样具有该权限。" else ""),
        detail = command + "\n\n执行位置：" +
            File(activeWorkspace).relativeTo(File(vm.workingDirectory)).path
                .takeIf { it != "." }?.let { "projects/" + it }.orEmpty()
                .ifBlank { "projects/" } +
            (if (interactive) "\n交互式 PTY 终端" else "\n流式命令") +
            (if (allowNetwork) "\n本次允许联网" else "\n已请求限制联网（Android 系统级隔离未验证）"),
        confirmLabel = if (TerminalCommandPolicy.evaluate(command).dangerous) "确认执行高风险命令" else "确认执行",
        destructive = TerminalCommandPolicy.evaluate(command).dangerous,
        onConfirm = {
            val accepted = command.trim()
            if (accepted.isNotBlank()) {
                recentCommands = ArrayList(
                    (listOf(accepted) + recentCommands.filterNot { it == accepted }).take(10)
                )
                historyPosition = TerminalHistoryPosition()
                vm.startTerminalCommand(accepted, tty = interactive, networkEnabled = allowNetwork)
                allowNetwork = false
            }
            confirm = false
        },
        onDismiss = { confirm = false }
    )
}

