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
    val selectedWorkspace by vm.activeWorkspace.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    val running by vm.running.collectAsState()
    val terminal by vm.terminalSession.collectAsState()
    val vmErrors by vm.errors.collectAsState()
    var folder by remember { mutableStateOf(root) }
    var pendingWorkspace by remember { mutableStateOf<File?>(null) }
    var focus by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createKind by remember { mutableStateOf("file") }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showProjectManager by rememberSaveable { mutableStateOf(false) }
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
        item(key = "workspace-path") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Project, null, tint = Ink.jade, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前目录", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    Text(folder.relativeTo(root).path.takeIf { it != "." }
                        ?.let { "projects/" + it } ?: "projects/",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    folder.parentFile?.let {
                        if (it.path == root.path ||
                            it.path.startsWith(root.path + File.separator)) folder = it
                    }
                }, enabled = folder != root,
                    modifier = Modifier.testTag("workspace-parent")) {
                    CodexIcon(CodexGlyph.ArrowBack, "上一级")
                }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !actionBusy, modifier = Modifier.testTag("workspace-import")) {
                    CodexIcon(CodexGlyph.FileUpload, "导入")
                }
            }
        }
        item(key = "workspace-context") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("任务项目", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    Text(PresentationPolicy.workspaceName(selectedWorkspace),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selectedWorkspace != folder.canonicalPath) {
                    TextButton(onClick = { pendingWorkspace = folder },
                        enabled = !running && !terminal.running && !actionBusy,
                        modifier = Modifier.testTag("workspace-select-current")) {
                        Text("设为当前项目", maxLines = 1)
                    }
                } else {
                    StatusPill("当前项目", true)
                }
                IconButton(onClick = { showProjectManager = true },
                    modifier = Modifier.testTag("workspace-project-management")) {
                    CodexIcon(CodexGlyph.AccountTree, "管理项目")
                }
            }
        }
        vmErrors["workspace"]?.let { warning ->
            item { ErrorText(warning) }
        }
        if (notice.isNotBlank()) item {
            CodexNotice(notice, if (notice.startsWith("已")) NoticeTone.Success else NoticeTone.Warning)
        }
        if (actionBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            OutlinedButton(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("workspace-create-toggle")
            ) {
                CodexIcon(CodexGlyph.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新建文件或文件夹")
            }
            if (showCreate) CodexDetailSheet(
                title = "新建项目内容",
                subtitle = "在当前目录创建文件或文件夹",
                onDismiss = { showCreate = false }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FilterChip(selected = createKind == "file",
                        onClick = { createKind = "file" }, label = { Text("文本文件") },
                        leadingIcon = { CodexIcon(CodexGlyph.Description, null) })
                    FilterChip(selected = createKind == "folder",
                        onClick = { createKind = "folder" }, label = { Text("文件夹") },
                        modifier = Modifier.testTag("workspace-folder-mode"),
                        leadingIcon = { CodexIcon(CodexGlyph.CreateNewFolder, null) })
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
                        showCreate = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        refresh++
                    } catch (failure: Exception) {
                        notice = "创建失败：" + (failure.message ?: "未知错误")
                    }
                }, enabled = createName.isNotBlank() && !actionBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("workspace-create")) {
                    CodexIcon(CodexGlyph.Add, null)
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
                icon = CodexGlyph.Project)
        }
        items(children, key = { it.absolutePath }) { file ->
            var optionsOpen by remember(file.path) { mutableStateOf(false) }
            Surface(
                color = Ink.panel,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onLongClick = { if (!actionBusy) optionsOpen = true },
                    onClick = {
                        val safe = file.canonicalFile
                        if (safe.path.startsWith(root.path + File.separator)) {
                            if (safe.isDirectory) folder = safe else focus = safe
                        } else notice = "已阻止打开工作区以外的文件链接"
                    }
                )
            ) {
                Row(Modifier.padding(start = 14.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CodexIcon(if (file.isDirectory) CodexGlyph.Folder
                        else CodexGlyph.Description,
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
                            CodexIcon(CodexGlyph.MoreVert, "管理 " + file.name)
                        }
                        if (optionsOpen) CodexActionSheet(
                            title = "管理 " + file.name,
                            onDismiss = { optionsOpen = false },
                            actions = listOf(
                                CodexSheetAction(
                                    label = "重命名",
                                    description = "更改当前文件或文件夹的名称",
                                    icon = CodexGlyph.Edit,
                                    onClick = {
                                        renameName = file.name
                                        fileAction = "rename" to file
                                    }
                                ),
                                CodexSheetAction(
                                    label = "复制",
                                    description = "在当前目录建立一份副本",
                                    icon = CodexGlyph.ContentCopy,
                                    onClick = {
                                        copyName = if (file.isDirectory) file.name + "-副本"
                                            else file.nameWithoutExtension + "-副本" +
                                                (file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
                                        fileAction = "copy" to file
                                    }
                                ),
                                CodexSheetAction(
                                    label = "删除",
                                    description = "删除前将再次确认目标",
                                    icon = CodexGlyph.DeleteOutline,
                                    onClick = { fileAction = "delete" to file },
                                    destructive = true
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    if (showProjectManager) {
        ProjectRegistryPanel(vm, folder, onDismiss = { showProjectManager = false })
    }
    pendingWorkspace?.let { target ->
        CodexConfirmDialog(
            title = "切换任务项目",
            message = if (activeThread == null)
                "后续新对话和本地终端将在选定目录工作。"
            else "当前会话会保留在历史中，切换后将从新会话继续工作。",
            detail = target.relativeTo(root).path.takeIf { it != "." }
                ?.let { "projects/" + it } ?: "projects/",
            confirmLabel = "确认使用此项目",
            onConfirm = {
                try {
                    vm.selectWorkspace(target)
                    notice = "已切换任务项目：" + target.name
                    pendingWorkspace = null
                } catch (error: Exception) {
                    notice = "切换失败：" + (error.message ?: "目标目录不可用")
                    pendingWorkspace = null
                }
            },
            onDismiss = { pendingWorkspace = null }
        )
    }
    fileAction?.let { (kind, target) ->
        if (kind == "rename") {
            CodexDialogFrame(
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
            CodexDialogFrame(
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
            IconButton(onClick = onBack) { CodexIcon(CodexGlyph.ArrowBack, "返回文件列表") }
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
            }, enabled = editable) { CodexIcon(CodexGlyph.Save, null); Text("保存") }
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
fun ReviewPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    ScreenColumn {
        SectionIntro("代码审查", "选择范围并提交给当前会话。")
        ReviewScopePanel(vm, onStarted = { navigate("chat") }, onSelectThread = { navigate("threads") })
    }
}
