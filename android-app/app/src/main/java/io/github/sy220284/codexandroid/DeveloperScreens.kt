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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun WorkspacePage(vm: CodexViewModel) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val root = remember { File(vm.workingDirectory).canonicalFile }
    var folder by remember { mutableStateOf(root) }
    var focus by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createKind by remember { mutableStateOf("file") }
    var fileAction by remember { mutableStateOf<Pair<String, File>?>(null) }
    var renameName by remember { mutableStateOf("") }
    var copyName by remember { mutableStateOf("") }
    var actionBusy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val importFolder = folder.canonicalFile
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = ctx.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: throw IllegalStateException("无法读取所选文档名称")
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法打开所选文档")
                    input.use { stream ->
                        if (name.endsWith(".zip", ignoreCase = true))
                            WorkspaceImport.importZip(root, importFolder, name, stream)
                        else
                            WorkspaceImport.importFile(root, importFolder, name, stream)
                    }
                }
                notice = result
                refresh++
            } catch (failure: Exception) {
                notice = "导入失败：" + (failure.message ?: "未知错误")
                refresh++
            }
        }
    }
    LaunchedEffect(folder) {
        try {
            withContext(Dispatchers.IO) { WorkspaceImport.cleanAbandoned(root, folder) }
        } catch (failure: Exception) {
            notice = "导入缓存清理失败：" + (failure.message ?: "未知错误")
        }
    }

    if (focus != null) {
        FileEditor(focus!!, root, onBack = { focus = null; refresh++ })
        return
    }
    BackHandler(enabled = folder != root) {
        folder = folder.parentFile?.takeIf {
            it.path == root.path || it.path.startsWith(root.path + File.separator)
        } ?: root
    }
    val children = remember(folder, refresh) {
        folder.listFiles()?.filterNot { WorkspaceImport.isStagingName(it.name) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }
                .thenBy { it.name.lowercase() }) ?: emptyList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("workspace-list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { SectionIntro("LOCAL / FILES", "工作区文件",
            "本机工程与文件统一管理，支持新建、导入、复制、重命名与安全删除。") }
        item {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("当前目录", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                Text(folder.relativeTo(root).path.takeIf { it != "." }
                    ?.let { "projects/" + it } ?: "projects/",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        folder.parentFile?.let {
                            if (it.path == root.path ||
                                it.path.startsWith(root.path + File.separator)) folder = it
                        }
                    }, enabled = folder != root) {
                        Icon(Icons.Outlined.ArrowBack, null)
                        Spacer(Modifier.width(5.dp))
                        Text("上一级")
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !actionBusy) {
                        Icon(Icons.Outlined.FileUpload, null)
                        Spacer(Modifier.width(6.dp))
                        Text("导入")
                    }
                }
            }
        }
        if (notice.isNotBlank()) item {
            CodexNotice(notice, if (notice.startsWith("已")) NoticeTone.Success else NoticeTone.Warning)
        }
        if (actionBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("新建项目内容", style = MaterialTheme.typography.titleMedium)
                Text("在当前目录创建文件或文件夹。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FilterChip(selected = createKind == "file",
                        onClick = { createKind = "file" }, label = { Text("文本文件") },
                        leadingIcon = { Icon(Icons.Outlined.Description, null) })
                    FilterChip(selected = createKind == "folder",
                        onClick = { createKind = "folder" }, label = { Text("文件夹") },
                        modifier = Modifier.testTag("workspace-folder-mode"),
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                }
                OutlinedTextField(createName, { createName = it },
                    label = { Text(if (createKind == "file") "文件名（如 README.md）" else "文件夹名称") },
                    modifier = Modifier.fillMaxWidth().testTag("workspace-new-name"), singleLine = true)
                OutlinedButton(onClick = {
                    try {
                        val created = if (createKind == "file")
                            WorkspaceFileActions.createTextFile(root, folder, createName)
                        else WorkspaceFileActions.createFolder(root, folder, createName)
                        notice = "已创建：" + created.name
                        createName = ""
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        refresh++
                    } catch (failure: Exception) {
                        notice = "创建失败：" + (failure.message ?: "未知错误")
                    }
                }, enabled = createName.isNotBlank() && !actionBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("workspace-create")) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (createKind == "file") "创建文本文件" else "创建文件夹")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("目录内容", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text("${children.size} 项", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
        }
        if (children.isEmpty()) item {
            CodexEmptyState("这个目录还没有内容", "可导入 ZIP 工程，也可以新建文件夹开始。",
                icon = Icons.Outlined.FolderOpen)
        }
        items(children, key = { it.absolutePath }) { file ->
            var optionsOpen by remember(file.path) { mutableStateOf(false) }
            Surface(
                color = Ink.panel,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
                modifier = Modifier.fillMaxWidth().clickable {
                    val safe = file.canonicalFile
                    if (safe.path.startsWith(root.path + File.separator)) {
                        if (safe.isDirectory) folder = safe else focus = safe
                    } else notice = "已阻止打开工作区以外的文件链接"
                }
            ) {
                Row(Modifier.padding(start = 14.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (file.isDirectory) Icons.Outlined.Folder
                        else Icons.Outlined.Description,
                        contentDescription = null, tint = Ink.copper,
                        modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(if (file.isDirectory) "文件夹" else "${file.length() / 1024} KB",
                            color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Box {
                        IconButton(onClick = { optionsOpen = true },
                            enabled = !actionBusy,
                            modifier = Modifier.testTag("workspace-menu-" + file.name)) {
                            Icon(Icons.Outlined.MoreVert, "管理 " + file.name)
                        }
                        DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                            DropdownMenuItem(text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = {
                                    optionsOpen = false
                                    renameName = file.name
                                    fileAction = "rename" to file
                                })
                            DropdownMenuItem(text = { Text("复制") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                onClick = {
                                    optionsOpen = false
                                    copyName = if (file.isDirectory) file.name + "-副本"
                                        else file.nameWithoutExtension + "-副本" +
                                            (file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
                                    fileAction = "copy" to file
                                })
                            DropdownMenuItem(text = { Text("删除") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Ink.red) },
                                onClick = {
                                    optionsOpen = false
                                    fileAction = "delete" to file
                                })
                        }
                    }
                }
            }
        }
    }

    fileAction?.let { (kind, target) ->
        if (kind == "rename") {
            AlertDialog(
                onDismissRequest = { fileAction = null },
                title = { Text("重命名") },
                containerColor = Ink.elevated,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("对象：" + target.name, color = Ink.muted)
                        OutlinedTextField(renameName, { renameName = it },
                            label = { Text("新名称") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = {
                        actionBusy = true
                        fileAction = null
                        scope.launch {
                            try {
                                val changed = withContext(Dispatchers.IO) {
                                    WorkspaceFileActions.rename(root, target, renameName)
                                }
                                notice = "已重命名为：" + changed.name
                                refresh++
                            } catch (failure: Exception) {
                                notice = "重命名失败：" + (failure.message ?: "未知错误")
                            } finally {
                                actionBusy = false
                            }
                        }
                    }, enabled = renameName.isNotBlank() && !actionBusy) {
                        Text("保存名称")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileAction = null }) { Text("取消") }
                }
            )
        } else if (kind == "copy") {
            AlertDialog(
                onDismissRequest = { fileAction = null },
                title = { Text("复制到当前目录") },
                containerColor = Ink.elevated,
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("来源：" + target.relativeTo(root).path,
                            color = Ink.muted)
                        OutlinedTextField(copyName, { copyName = it },
                            label = { Text("新文件或文件夹名称") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Text("创建独立副本；如已存在同名文件则停止操作。",
                            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = {
                        val finalName = copyName.trim()
                        val finalFolder = folder
                        actionBusy = true
                        fileAction = null
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        scope.launch {
                            try {
                                val copied = withContext(Dispatchers.IO) {
                                    WorkspaceFileActions.copy(root, target, finalFolder, finalName)
                                }
                                notice = "已复制：" + copied.name
                                refresh++
                            } catch (failure: Exception) {
                                notice = "复制失败：" + (failure.message ?: "未知错误")
                            } finally {
                                actionBusy = false
                            }
                        }
                    }, enabled = copyName.isNotBlank() && !actionBusy) {
                        Text("确认复制")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileAction = null }) { Text("取消") }
                }
            )
        } else if (kind == "delete") {
            CodexConfirmDialog(
                title = "确认删除" + if (target.isDirectory) "文件夹" else "文件",
                message = if (target.isDirectory)
                    "将删除此文件夹及其全部内容。该操作无法撤销。"
                    else "将删除选中的文件。该操作无法撤销。",
                detail = target.relativeTo(root).path,
                destructive = true, confirmLabel = "永久删除",
                onConfirm = {
                    actionBusy = true
                    fileAction = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                WorkspaceFileActions.delete(root, target)
                            }
                            notice = "已删除：" + target.name
                            refresh++
                        } catch (failure: Exception) {
                            notice = "删除失败：" + (failure.message ?: "未知错误")
                        } finally {
                            actionBusy = false
                        }
                    }
                },
                onDismiss = { fileAction = null }
            )
        }
    }

}

@Composable
private fun FileEditor(file: File, root: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var content by remember(file.path) { mutableStateOf(if (file.length() <= 1_000_000L) file.readText() else "") }
    var message by remember { mutableStateOf("") }
    val editable = file.length() <= 1_000_000L
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回文件列表") }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("本地文件编辑", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            require(file.canonicalPath.startsWith(root.path + File.separator)) { "编辑路径超出工作区" }
                            WorkspaceFileActions.saveText(root, file, content)
                        }
                        message = "已保存到工作区"
                    } catch (failure: Exception) { message = failure.message ?: "保存失败" }
                }
            }, enabled = editable) { Icon(Icons.Outlined.Save, null); Text("保存") }
        }
        if (message.isNotEmpty()) Text(message, color = Ink.jade)
        if (!editable) Text("文件超过 1 MB，请通过专用工具处理。", color = Ink.red)
        OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxSize(),
            enabled = editable, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
fun DiffPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    DiffReaderPage(vm, navigate)
}

@Composable
fun TerminalPage(vm: CodexViewModel) {
    var command by rememberSaveable { mutableStateOf("") }
    var recentCommands by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var historyPosition by remember { mutableStateOf(TerminalHistoryPosition()) }
    var expandedOutput by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val session by vm.terminalSession.collectAsState()
    val errors by vm.errors.collectAsState()

    ScreenColumn {
        SectionIntro("LOCAL / COMMAND", "本地终端",
            "运行本机工作区命令。执行期间可以查看实时输出、输入内容或主动停止。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("Android 进程级隔离", style = MaterialTheme.typography.titleMedium)
            Text("执行位置：本机工作区", color = Ink.muted,
                style = MaterialTheme.typography.labelMedium)
            Text("命令可能修改应用工作区。执行前显示完整内容并请你确认。", color = Ink.muted)
            OutlinedTextField(
                command,
                onValueChange = {
                    command = it
                    // Editing an old command starts a fresh history traversal.
                    historyPosition = TerminalHistoryPosition()
                },
                label = { Text("Shell 命令") },
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
            OutlinedButton(onClick = { confirm = true },
                enabled = command.isNotBlank() && !session.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("执行命令")
            }
        }
        errors["terminal"]?.let { ErrorText(it) }

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
                session.error?.let { ErrorText(it) }
                if (session.outputTruncated) {
                    CodexNotice("已保留终端最近 128KiB 输出，较早日志已从界面移出。",
                        NoticeTone.Warning)
                }
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
        }
    }
    if (confirm) CodexConfirmDialog(
        title = "确认执行命令",
        message = "将由 Codex 在当前工作区执行以下命令。请检查执行内容及目录。",
        detail = command + "\n\n执行位置：本机工作区",
        confirmLabel = "确认执行",
        destructive = true,
        onConfirm = {
            val accepted = command.trim()
            if (accepted.isNotBlank()) {
                recentCommands = ArrayList(
                    (listOf(accepted) + recentCommands.filterNot { it == accepted }).take(10)
                )
                historyPosition = TerminalHistoryPosition()
                vm.startTerminalCommand(accepted)
            }
            confirm = false
        },
        onDismiss = { confirm = false }
    )
}

@Composable
fun ReviewPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    ScreenColumn {
        SectionIntro("CODE / QUALITY", "代码审查", "选择范围并提交给当前会话。")
        ReviewScopePanel(vm, onStarted = { navigate("chat") }, onSelectThread = { navigate("threads") })
    }
}
