package io.github.sy220284.codexandroid

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
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

private data class WorkspaceDirectoryEntry(val file: File, val directory: Boolean, val bytes: Long)

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
    val terminalRunning by vm.terminalSession.collectProjection { it.running }
    val vmErrors by vm.errors.collectEntries("workspace")
    var folder by remember { mutableStateOf(root) }
    var pendingWorkspace by remember { mutableStateOf<File?>(null) }
    var focus by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createKind by remember { mutableStateOf("file") }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showProjectManager by rememberSaveable { mutableStateOf(false) }
    var selectedEntry by remember(folder) { mutableStateOf<WorkspaceDirectoryEntry?>(null) }
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

    // Shared storage may be changed by the native core, terminal or system file manager.
    DisposableEffect(folder) {
        var refreshJob: kotlinx.coroutines.Job? = null
        val observer = object : android.os.FileObserver(folder,
            android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
                android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
                android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.DELETE_SELF or
                android.os.FileObserver.MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) {
                scope.launch {
                    refreshJob?.cancel()
                    refreshJob = scope.launch {
                        kotlinx.coroutines.delay(180)
                        refresh++
                    }
                }
            }
        }
        observer.startWatching()
        onDispose { observer.stopWatching(); refreshJob?.cancel() }
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
    // Keep the last directory snapshot visible until its replacement is ready.
    val directoryResult by key(folder) {
        produceState<Result<List<WorkspaceDirectoryEntry>>?>(null, folder, refresh) {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val entries = requireNotNull(folder.listFiles()) { "无法读取当前目录" }
                    entries.asSequence()
                        .filterNot { WorkspaceImport.isStagingName(it.name) }
                        .map { WorkspaceDirectoryEntry(it, it.isDirectory, it.length()) }
                        .sortedWith(compareBy<WorkspaceDirectoryEntry> { !it.directory }
                            .thenBy { it.file.name.lowercase() }).toList()
                }
                value = Result.success(entries)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { value = Result.failure(failure) }
        }
    }
    val children = directoryResult?.getOrNull().orEmpty()
    var fileQuery by remember(folder) { mutableStateOf("") }
    val visibleChildren = remember(children, fileQuery) {
        children.filter { it.file.name.contains(fileQuery.trim(), ignoreCase = true) }
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
                        ?.let { (WorkspaceStorage.DISPLAY_ROOT + "/") + it } ?: (WorkspaceStorage.DISPLAY_ROOT + "/"),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CodexIconAction(onClick = {
                    folder.parentFile?.let {
                        if (it.path == root.path ||
                            it.path.startsWith(root.path + File.separator)) folder = it
                    }
                }, enabled = folder != root,
                    modifier = Modifier.testTag("workspace-parent")) {
                    CodexIcon(CodexGlyph.ArrowBack, "上一级")
                }
                CodexIconAction(onClick = { picker.launch(arrayOf("*/*")) },
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
                    CodexTextAction(onClick = { pendingWorkspace = folder },
                        enabled = !running && !terminalRunning && !actionBusy,
                        modifier = Modifier.testTag("workspace-select-current")) {
                        Text("设为当前项目", maxLines = 1)
                    }
                } else {
                    StatusPill("当前项目", true)
                }
                CodexIconAction(onClick = { showProjectManager = true },
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
        if (actionBusy) item { AtlasOperationProgress("正在处理文件操作…") }
        item {
            CodexSecondaryButton(
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
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AtlasExclusiveChip(selected = createKind == "file",
                        onClick = { createKind = "file" }, label = { Text("文本文件") },
                        leadingIcon = { CodexIcon(CodexGlyph.Description, null) })
                    AtlasExclusiveChip(selected = createKind == "folder",
                        onClick = { createKind = "folder" }, label = { Text("文件夹") },
                        modifier = Modifier.testTag("workspace-folder-mode"),
                        leadingIcon = { CodexIcon(CodexGlyph.CreateNewFolder, null) })
                }
                AtlasFormField(createName, { createName = it },
                    label = if (createKind == "file") "文件名（如 README.md）" else "文件夹名称",
                    modifier = Modifier.testTag("workspace-new-name"))
                CodexSecondaryButton(onClick = {
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
                Text(when {
                    directoryResult == null -> "读取中"
                    directoryResult?.isFailure == true -> "读取失败"
                    fileQuery.isBlank() -> "${children.size} 项"
                    else -> "${visibleChildren.size} / ${children.size} 项"
                }, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
        }
        if (children.isNotEmpty()) item(key = "workspace-search") {
            AtlasSearchField(fileQuery, { fileQuery = it }, "搜索当前目录",
                onClear = { fileQuery = "" }, modifier = Modifier.testTag("workspace-file-search"))
        }
        if (children.isNotEmpty() && visibleChildren.isEmpty()) item {
            CodexEmptyState("没有匹配的文件", "试试其他文件名。",
                actionLabel = "清除搜索", onAction = { fileQuery = "" })
        }
        when {
            directoryResult == null -> item(key = "workspace-directory-loading") {
                AtlasOperationProgress("正在读取目录", Modifier.testTag("workspace-directory-loading"))
            }
            directoryResult?.isFailure == true -> item(key = "workspace-directory-error") {
                CodexRecoveryState("目录读取失败",
                    directoryResult?.exceptionOrNull()?.message ?: "无法读取当前目录",
                    "重新读取", { refresh++ })
            }
            children.isEmpty() -> item {
                CodexEmptyState("这个目录还没有内容", "可导入 ZIP 工程，也可以新建文件夹开始。",
                    icon = CodexGlyph.Project)
            }
        }
        items(visibleChildren, key = { it.file.absolutePath }) { entry ->
            val file = entry.file
            Surface(
                color = Ink.panel,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onLongClick = { if (!actionBusy) selectedEntry = entry },
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
                    CodexIcon(if (entry.directory) CodexGlyph.Folder
                        else CodexGlyph.Description,
                        contentDescription = null, tint = Ink.copper,
                        modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(if (entry.directory) "文件夹" else "${entry.bytes / 1024} KB",
                            color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    Box {
                    CodexIconAction(onClick = { selectedEntry = entry }, enabled = !actionBusy,
                        modifier = Modifier.testTag("workspace-menu-" + file.name)) {
                        CodexIcon(CodexGlyph.MoreVert, "管理 " + file.name)
                    }
                        if (selectedEntry?.file == file) CodexFloatingActionMenu(
            title = "管理 " + file.name,
            onDismiss = { selectedEntry = null },
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
                        copyName = if (entry.directory) file.name + "-副本"
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
                ?.let { (WorkspaceStorage.DISPLAY_ROOT + "/") + it } ?: (WorkspaceStorage.DISPLAY_ROOT + "/"),
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
                        AtlasFormField(renameName, { renameName = it }, "新名称")
                    }
                },
                confirmButton = {
                    CodexSecondaryButton(onClick = {
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
                    CodexTextAction(onClick = { fileAction = null }) { Text("取消") }
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
                        AtlasFormField(copyName, { copyName = it }, "新文件或文件夹名称")
                        Text("创建独立副本；如已存在同名文件则停止操作。",
                            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    CodexSecondaryButton(onClick = {
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
                    CodexTextAction(onClick = { fileAction = null }) { Text("取消") }
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
