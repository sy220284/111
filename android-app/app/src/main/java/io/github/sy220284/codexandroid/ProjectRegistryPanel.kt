package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Codex's durable project registry is separate from local file deletion. */
@Composable
fun ProjectRegistryPanel(vm: CodexViewModel, currentFolder: File, onDismiss: () -> Unit) {
    val status by vm.bridge.status.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val selectedId by vm.activeProjectId.collectAsStateWithLifecycle()
    val currentThread by vm.activeThread.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val selectedPath by vm.activeWorkspace.collectAsStateWithLifecycle()
    val root = File(vm.workingDirectory).canonicalFile
    val name = currentFolder.name.ifBlank { "Codex" }
    var confirmCreate by remember { mutableStateOf(false) }
    var withThread by remember { mutableStateOf(false) }
    var projectPages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var nextPageCursor by remember { mutableStateOf("") }
    val revision by vm.projectCatalogRevision.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var detailsId by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var actionMenuId by remember { mutableStateOf("") }
    fun reload() {
        projectPages = emptyList()
        nextPageCursor = ""
        vm.refresh("projectsCatalog", "project/list", JSONObject().put("limit", 100)) { response ->
            projectPages = records(response)
            nextPageCursor = response.optString("nextCursor")
        }
    }
    fun loadMore() {
        if (nextPageCursor.isBlank()) return
        val cursor = nextPageCursor
        vm.refresh("projectsCatalogMore", "project/list",
            JSONObject().put("limit", 100).put("cursor", cursor)) { response ->
            val seen = projectPages.map { it.optString("id") }.toSet()
            projectPages = projectPages + records(response).filter { it.optString("id") !in seen }
            nextPageCursor = response.optString("nextCursor")
        }
    }
    LaunchedEffect(status, revision) {
        if (status == EngineStatus.Connected) reload()
    }
    val projects = projectPages
    CodexDetailSheet(
        title = "项目列表与管理", startExpanded = true,
        subtitle = "登记、切换与整理当前工作区项目",
        onDismiss = onDismiss
    ) {
        Text("将当前文件夹加入项目列表，之后便可快速切换项目。",
            style = MaterialTheme.typography.bodySmall)
            if (status != EngineStatus.Connected) {
                Text("请先连接运行服务，再管理项目。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                CodexSecondaryButton(onClick = { reload() },
                    enabled = "projectsCatalog" !in loading) {
                    Text("刷新项目列表")
                }
                errors["projectsCatalog"]?.let { ErrorText("项目列表读取失败：$it") }
                errors["projectsCatalogMore"]?.let { ErrorText("更多项目读取失败：$it") }
                errors["projectWrite"]?.let { ErrorText("项目操作失败：$it") }
                if (errorText.isNotEmpty()) ErrorText(errorText)
                Text("当前目录：" + currentFolder.relativeTo(root).path.takeIf { it != "." }
                    ?.let { (WorkspaceStorage.DISPLAY_ROOT + "/") + it }.orEmpty().ifBlank { (WorkspaceStorage.DISPLAY_ROOT + "/") },
                    style = MaterialTheme.typography.bodySmall)
                if (!currentThread.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CodexCheckBox(checked = withThread, onCheckedChange = { withThread = it },
                            enabled = !running && "projectWrite" !in loading)
                        Text("同时关联当前会话", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                CodexSecondaryButton(onClick = { confirmCreate = true },
                    enabled = name.isNotBlank() && !running && "projectWrite" !in loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (withThread && currentThread != null) "登记项目并关联会话"
                        else "登记当前目录")
                }
                if ("projectsCatalog" in loading && projects.isEmpty()) {
                    AtlasLoadingResults("正在读取项目", placeholderRows = 3)
                }
                if ("projectsCatalog" !in loading && projects.isEmpty() &&
                    errors["projectsCatalog"] == null) {
                    CodexEmptyState("暂无登记项目",
                        "直接登记当前工作目录，默认使用文件夹名称，可在项目管理中修改。")
                }
                projects.forEachIndexed { index, project ->
                    val projectId = project.optString("id")
                    val displayName = project.optString("name").ifBlank { "未命名项目" }
                    val rootPath = project.optJSONArray("roots")
                        ?.optJSONObject(0)?.optString("path").orEmpty()
                        .let { if (it.isBlank()) it else WorkspaceStorage.remap(vm.getApplication(), it) }
                    val location = when {
                        rootPath.isBlank() -> "目录信息缺失"
                        rootPath == root.path -> (WorkspaceStorage.DISPLAY_ROOT + "/")
                        rootPath.startsWith(root.path + File.separator) ->
                            (WorkspaceStorage.DISPLAY_ROOT + "/") + rootPath.removePrefix(root.path + File.separator)
                        else -> "目录不在当前本机工作区"
                    }
                    val canModify = !running && "projectWrite" !in loading
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(displayName, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(location, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        CodexSecondaryButton(onClick = {
                            try {
                                vm.selectProject(projectId, File(rootPath))
                                errorText = ""
                            } catch (error: Exception) {
                                errorText = error.message ?: "无法使用此项目"
                            }
                        }, enabled = projectId.isNotBlank() && rootPath.isNotBlank() &&
                            canModify && (selectedId != projectId || selectedPath != rootPath),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (selectedId == projectId) "当前项目" else "使用")
                        }
                        CodexIconAction(
                            onClick = { actionMenuId = projectId },
                            enabled = projectId.isNotBlank(),
                            modifier = Modifier.size(48.dp)
                        ) {
                            CodexIcon(CodexGlyph.MoreVert, "管理" + displayName)
                        }
                        if (actionMenuId == projectId && projectId.isNotBlank()) {
                            CodexActionDialog(
                                title = "管理项目 · " + displayName,
                                onDismiss = { actionMenuId = "" },
                                actions = listOf(
                                    CodexSheetAction(
                                        label = "查看详情",
                                        icon = CodexGlyph.Info,
                                        onClick = {
                                            detailsId = projectId
                                            vm.refresh("projectDetail", "project/read",
                                                ProjectRequestContract.read(projectId))
                                        }
                                    ),
                                    CodexSheetAction(
                                        label = "重命名",
                                        icon = CodexGlyph.Edit,
                                        enabled = canModify,
                                        onClick = {
                                            editing = project
                                            renameText = displayName
                                        }
                                    ),
                                    CodexSheetAction(
                                        label = "向上移动",
                                        icon = CodexGlyph.ArrowUpward,
                                        enabled = canModify && index > 0,
                                        onClick = {
                                            vm.rpc("projectWrite", "project/move",
                                                ProjectRequestContract.move(projectId,
                                                    projects.getOrNull(index - 1)?.optString("id")),
                                                after = { reload() })
                                        }
                                    ),
                                    CodexSheetAction(
                                        label = "向下移动",
                                        icon = CodexGlyph.ArrowDownward,
                                        enabled = canModify && index < projects.lastIndex,
                                        onClick = {
                                            vm.rpc("projectWrite", "project/move",
                                                ProjectRequestContract.move(projectId,
                                                    projects.getOrNull(index + 2)?.optString("id")),
                                                after = { reload() })
                                        }
                                    ),
                                    CodexSheetAction(
                                        label = "移除项目登记",
                                        icon = CodexGlyph.DeleteOutline,
                                        description = "只移除登记，保留本机目录；确认后执行",
                                        enabled = canModify,
                                        destructive = true,
                                        onClick = { deleting = project }
                                    )
                                )
                            )
                        }
                    }
                }
                if (nextPageCursor.isNotBlank()) {
                    CodexSecondaryButton(onClick = { loadMore() },
                        enabled = "projectsCatalogMore" !in loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if ("projectsCatalogMore" in loading) "正在读取更多项目…" else "加载更多项目")
                    }
                }
            }
    }
    if (detailsId.isNotBlank()) {
        val detail = data["projectDetail"]?.optJSONObject("project")?.takeIf {
            it.optString("id") == detailsId
        }
        CodexInfoDialog(
            title = projectPages.firstOrNull { it.optString("id") == detailsId }
                ?.optString("name").orEmpty().ifBlank { "项目详情" },
            onDismiss = { detailsId = "" }
        ) {
            when {
                detail != null -> {
                    Text("已登记目录：" + (detail.optJSONArray("roots")?.length() ?: 0) + " 个")
                    Text("这里仅管理项目登记与关联，保留本机目录和文件。",
                        color = Ink.muted)
                }
                "projectDetail" in loading -> AtlasOperationProgress("正在读取项目详情")
                errors["projectDetail"] != null -> ErrorText(
                    "详情读取失败：" +
                        PresentationPolicy.readableError(errors["projectDetail"].orEmpty()))
                else -> AtlasFieldFeedback("暂无详情，重新打开后可再次读取")
            }
        }
    }
    if (confirmCreate) {
        CodexDialogFrame(onDismissRequest = { confirmCreate = false },
            title = { Text("登记 Codex 项目") },
            text = {
                Column {
                    Text("将以「$name」登记当前目录，原有文件保持不变。")
                    if (currentThread != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodexCheckBox(checked = withThread, onCheckedChange = { withThread = it })
                            Text("将当前会话一并归入新项目")
                        }
                    }
                }
            },
            confirmButton = {
                CodexTextAction(onClick = {
                    val payload = if (withThread && currentThread != null)
                        ProjectRequestContract.importCurrentThread(root, currentFolder, name,
                            currentThread!!)
                    else ProjectRequestContract.create(root, currentFolder, name)
                    vm.rpc("projectWrite",
                        if (withThread && currentThread != null) "project/import" else "project/create",
                        payload, after = {
                            withThread = false
                            reload()
                        })
                    confirmCreate = false
                }, enabled = name.isNotBlank()) { Text("确认登记") }
            },
            dismissButton = { CodexTextAction(onClick = { confirmCreate = false }) { Text("取消") } }
        )
    }
    editing?.let { item ->
        CodexDialogFrame(onDismissRequest = { editing = null },
            title = { Text("修改项目名称") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionReplyRow(renameText, { renameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("项目名称") }, singleLine = true)
                    errors["projectWrite"]?.let {
                        Text("保存失败：" + PresentationPolicy.readableError(it), color = Ink.red)
                    }
                }
            },
            confirmButton = { CodexTextAction(onClick = {
                vm.rpc("projectWrite", "project/update",
                    ProjectRequestContract.rename(item.optString("id"), renameText),
                    after = {
                        editing = null
                        reload()
                    })
            }, enabled = renameText.isNotBlank()) { Text("保存") } },
            dismissButton = { CodexTextAction(onClick = { editing = null }) { Text("取消") } }
        )
    }
    deleting?.let { item ->
        CodexConfirmDialog(
            title = "移除项目记录",
            message = "项目将从 Codex 项目库移除，并解除对应会话的项目归属；本机目录和文件保留。",
            detail = item.optString("name"),
            confirmLabel = "确认移除记录",
            destructive = true,
            onConfirm = {
                val id = item.optString("id")
                vm.rpc("projectWrite", "project/delete",
                    ProjectRequestContract.read(id), after = {
                        if (vm.activeProjectId.value == id) vm.activeProjectId.value = null
                        reload()
                    })
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}
