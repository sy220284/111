package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Codex's durable project registry is separate from local file deletion. */
@Composable
fun ProjectRegistryPanel(vm: CodexViewModel, currentFolder: File) {
    val status by vm.bridge.status.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val selectedId by vm.activeProjectId.collectAsState()
    val currentThread by vm.activeThread.collectAsState()
    val running by vm.running.collectAsState()
    val selectedPath by vm.activeWorkspace.collectAsState()
    val root = File(vm.workingDirectory).canonicalFile
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var confirmCreate by remember { mutableStateOf(false) }
    var withThread by remember { mutableStateOf(false) }
    var projectPages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var nextPageCursor by remember { mutableStateOf("") }
    val revision by vm.projectCatalogRevision.collectAsState()
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var detailsId by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
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
    LaunchedEffect(expanded, status, revision) {
        if (expanded && status == EngineStatus.Connected) reload()
    }
    val projects = projectPages
    MaterialPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountTree, null)
            Spacer(Modifier.width(8.dp))
            Text("Codex 项目管理", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        Text("将本机目录登记为 Codex 项目，并关联后续任务。项目登记与文件管理分别操作。",
            style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            if (status != EngineStatus.Connected) {
                Text("先连接本机 Codex 核心后再管理项目。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = { reload() },
                    enabled = "projectsCatalog" !in loading) {
                    Text("刷新项目列表")
                }
                errors["projectsCatalog"]?.let { ErrorText("项目列表读取失败：$it") }
                errors["projectsCatalogMore"]?.let { ErrorText("更多项目读取失败：$it") }
                errors["projectWrite"]?.let { ErrorText("项目操作失败：$it") }
                if (errorText.isNotEmpty()) ErrorText(errorText)
                Text("当前目录：" + currentFolder.relativeTo(root).path.takeIf { it != "." }
                    ?.let { "projects/" + it }.orEmpty().ifBlank { "projects/" },
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("新项目名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        withThread = false
                        confirmCreate = true
                    }, enabled = name.isNotBlank() && "projectWrite" !in loading) {
                        Text("登记当前目录")
                    }
                    if (!currentThread.isNullOrBlank()) {
                        TextButton(onClick = {
                            withThread = true
                            confirmCreate = true
                        }, enabled = name.isNotBlank() && "projectWrite" !in loading) {
                            Text("同时关联当前会话")
                        }
                    }
                }
                projects.forEachIndexed { index, project ->
                    val projectId = project.optString("id")
                    val displayName = project.optString("name")
                    val rootPath = project.optJSONArray("roots")
                        ?.optJSONObject(0)?.optString("path").orEmpty()
                    HorizontalDivider()
                    Text(displayName, style = MaterialTheme.typography.titleSmall)
                    Text(rootPath.removePrefix(root.path).ifBlank { "/" },
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                vm.selectProject(projectId, File(rootPath))
                                errorText = ""
                            } catch (error: Exception) {
                                errorText = error.message ?: "无法使用此项目"
                            }
                        }, enabled = projectId.isNotBlank() && rootPath.isNotBlank() &&
                            !running && "projectWrite" !in loading &&
                            (selectedId != projectId || selectedPath != rootPath)) {
                            Text(if (selectedId == projectId) "当前项目" else "使用项目")
                        }
                        TextButton(onClick = {
                            detailsId = if (detailsId == projectId) "" else projectId
                            if (detailsId == projectId) vm.refresh(
                                "projectDetail", "project/read",
                                ProjectRequestContract.read(projectId))
                        }) { Text(if (detailsId == projectId) "收起" else "详情") }
                        TextButton(onClick = {
                            editing = project
                            renameText = displayName
                        }) { Text("改名") }
                        TextButton(onClick = { deleting = project }) { Text("移除") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            vm.rpc("projectWrite", "project/move",
                                ProjectRequestContract.move(projectId,
                                    projects.getOrNull(index - 1)?.optString("id")),
                                after = { reload() })
                        }, enabled = index > 0 && "projectWrite" !in loading) { Text("上移") }
                        TextButton(onClick = {
                            vm.rpc("projectWrite", "project/move",
                                ProjectRequestContract.move(projectId,
                                    projects.getOrNull(index + 2)?.optString("id")),
                                after = { reload() })
                        }, enabled = index < projects.lastIndex && "projectWrite" !in loading) {
                            Text("下移")
                        }
                    }
                    if (detailsId == projectId) {
                        data["projectDetail"]?.optJSONObject("project")?.takeIf {
                            it.optString("id") == projectId
                        }?.let { item ->
                            Text("目录数量：" + (item.optJSONArray("roots")?.length() ?: 0),
                                style = MaterialTheme.typography.bodySmall)
                            Text("项目编号：" + item.optString("id").take(16),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (nextPageCursor.isNotBlank()) {
                    OutlinedButton(onClick = { loadMore() },
                        enabled = "projectsCatalogMore" !in loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if ("projectsCatalogMore" in loading) "正在读取更多项目…" else "加载更多项目")
                    }
                }
            }
        }
    }
    if (confirmCreate) {
        AlertDialog(onDismissRequest = { confirmCreate = false },
            title = { Text("登记 Codex 项目") },
            text = {
                Column {
                    Text("项目目录及名称会写入本机 Codex 项目库。原有文件保持不变。")
                    if (currentThread != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = withThread, onCheckedChange = { withThread = it })
                            Text("将当前会话一并归入新项目")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = if (withThread && currentThread != null)
                        ProjectRequestContract.importCurrentThread(root, currentFolder, name,
                            currentThread!!)
                    else ProjectRequestContract.create(root, currentFolder, name)
                    vm.rpc("projectWrite",
                        if (withThread && currentThread != null) "project/import" else "project/create",
                        payload, after = { reload() })
                    name = ""
                    confirmCreate = false
                }, enabled = name.isNotBlank()) { Text("确认登记") }
            },
            dismissButton = { TextButton(onClick = { confirmCreate = false }) { Text("取消") } }
        )
    }
    editing?.let { item ->
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("修改项目名称") },
            text = { OutlinedTextField(renameText, { renameText = it },
                label = { Text("项目名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                vm.rpc("projectWrite", "project/update",
                    ProjectRequestContract.rename(item.optString("id"), renameText),
                    after = { reload() })
                editing = null
            }, enabled = renameText.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
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
