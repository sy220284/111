package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Server-backed paginated conversation library. Each mode has its own result key,
 * so home-screen refreshes cannot overwrite archived or searched results.
 */
@Composable
fun ThreadLibraryPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val running by vm.running.collectAsState()
    val activeId by vm.activeThread.collectAsState()
    var archived by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var searchTerm by rememberSaveable { mutableStateOf("") }
    var cursor by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<String?>>(emptyList()) }
    var rename by remember { mutableStateOf<JSONObject?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var action by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    val key = if (archived) "libraryArchived" else "libraryActive"
    val response = data[key]
    val entries = records(response)
    val nextCursor = response?.optString("nextCursor")?.takeIf { it.isNotBlank() }
    val busy = "threadLibraryAction" in loading

    fun load(pageCursor: String?) {
        val args = JSONObject().put("limit", 35).put("archived", archived)
        if (!pageCursor.isNullOrBlank()) args.put("cursor", pageCursor)
        if (searchTerm.isNotBlank()) args.put("searchTerm", searchTerm)
        vm.refresh(key, "thread/list", args)
    }
    LaunchedEffect(archived, searchTerm, cursor) { load(cursor) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            SectionIntro("WORKSPACE / THREADS", "会话资料库",
                "整理正在使用的会话与归档记录，所有操作交由 Codex 内核执行。")
        }
        item {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("会话管理", style = MaterialTheme.typography.titleMedium)
                        Text("按状态与标题从本地服务端读取", color = Ink.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { load(cursor) }, enabled = key !in loading) {
                        Icon(Icons.Outlined.Refresh, "刷新列表")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !archived, onClick = {
                        archived = false; cursor = null; history = emptyList()
                    }, label = { Text("活跃") })
                    FilterChip(selected = archived, onClick = {
                        archived = true; cursor = null; history = emptyList()
                    }, label = { Text("已归档") })
                }
                OutlinedTextField(searchText, { searchText = it },
                    label = { Text("搜索会话标题") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            val submitted = searchText.trim()
                            if (searchTerm == submitted && cursor == null) load(null)
                            else {
                                searchTerm = submitted; cursor = null; history = emptyList()
                            }
                        }, enabled = key !in loading) {
                            Icon(Icons.Outlined.ArrowForward, "开始搜索")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("搜索由服务端按标题匹配；通过游标翻页查看其余结果。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.newThread(); navigate("chat") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !running) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(7.dp))
                    Text("新建任务")
                }
            }
        }
        if (key in loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        errors[key]?.let { item { ErrorText(it) } }
        errors["threadLibraryAction"]?.let { item { ErrorText("会话操作失败：$it") } }
        if (response != null) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (archived) "归档记录" else "最近会话",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("本页 ${entries.size} 项", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (response != null && entries.isEmpty() && key !in loading && key !in errors) {
            item {
                CodexEmptyState(
                    if (archived) "没有匹配的归档记录" else "暂无匹配会话",
                    if (searchTerm.isNotBlank()) "调整搜索词后重新查询。"
                    else if (archived) "会话归档后会出现在这里，并可恢复到活跃列表。"
                    else "开始新任务后，可以在这里继续、整理和归档。",
                    actionLabel = if (searchTerm.isNotBlank()) "清除搜索" else null,
                    onAction = {
                        searchText = ""; searchTerm = ""; cursor = null; history = emptyList()
                    }
                )
            }
        }
        items(entries, key = { it.optString("id") }) { thread ->
            val id = thread.optString("id")
            val title = thread.optString("name").ifBlank {
                thread.optString("preview").ifBlank { "未命名会话" }
            }
            val canManage = !(running && activeId == id) && !busy
            var menu by remember(id) { mutableStateOf(false) }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Ink.copper.copy(alpha = .12f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(if (archived) Icons.Outlined.Inventory2 else Icons.Outlined.Forum,
                            null, tint = Ink.copper, modifier = Modifier.padding(10.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(thread.optString("cwd").ifBlank { id },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink.muted)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = canManage) {
                            Icon(Icons.Outlined.MoreVert, "管理会话")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("修改名称") }, onClick = {
                                menu = false; rename = thread; newName = thread.optString("name")
                            }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                            DropdownMenuItem(text = {
                                Text(if (archived) "从归档恢复" else "移入归档")
                            }, onClick = {
                                menu = false
                                action = (if (archived) "unarchive" else "archive") to thread
                            }, leadingIcon = {
                                Icon(if (archived) Icons.Outlined.Unarchive
                                    else Icons.Outlined.Archive, null)
                            })
                            DropdownMenuItem(text = { Text("永久删除") }, onClick = {
                                menu = false; action = "delete" to thread
                            }, leadingIcon = {
                                Icon(Icons.Outlined.DeleteOutline, null, tint = Ink.red)
                            })
                        }
                    }
                }
                val updatedAt = thread.optString("updatedAt").ifBlank {
                    thread.optString("createdAt")
                }
                if (updatedAt.isNotBlank()) {
                    Text("更新于 " + updatedAt.replace("T", " ").take(19),
                        color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                }
                if (running && activeId == id)
                    CodexNotice("该会话仍在执行，结束后可整理。")
                if (archived) {
                    OutlinedButton(onClick = { action = "unarchive" to thread },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = canManage) {
                        Icon(Icons.Outlined.Unarchive, null)
                        Spacer(Modifier.width(7.dp))
                        Text("恢复会话")
                    }
                } else {
                    Button(onClick = { vm.chooseThread(id); navigate("chat") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !running && id.isNotBlank()) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text("继续会话")
                    }
                }
            }
        }
        if (response != null && (nextCursor != null || history.isNotEmpty())) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        cursor = history.last()
                        history = history.dropLast(1)
                    }, enabled = history.isNotEmpty() && key !in loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("上一页")
                    }
                    Button(onClick = {
                        history = history + cursor
                        cursor = nextCursor
                    }, enabled = nextCursor != null && key !in loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("下一页")
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
            }
        }
    }

    rename?.let { thread ->
        val id = thread.optString("id")
        AlertDialog(
            onDismissRequest = { rename = null },
            containerColor = Ink.elevated,
            title = { Text("重命名会话") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新名称将同步到 Codex 本地会话记录。", color = Ink.muted)
                    OutlinedTextField(newName, { newName = it },
                        label = { Text("会话名称") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.rpc("threadLibraryAction", "thread/name/set",
                        JSONObject().put("threadId", id).put("name", newName.trim()),
                        after = {
                            load(cursor)
                            vm.refresh("threads", "thread/list", JSONObject().put("limit", 30))
                        })
                    rename = null
                }, enabled = newName.trim().isNotBlank() && id.isNotBlank() && !busy) {
                    Text("保存名称")
                }
            },
            dismissButton = {
                TextButton(onClick = { rename = null }) { Text("取消") }
            }
        )
    }
    action?.let { (kind, thread) ->
        val id = thread.optString("id")
        val title = thread.optString("name").ifBlank { id }
        val label = when (kind) {
            "delete" -> "永久删除"
            "archive" -> "归档"
            else -> "恢复"
        }
        CodexConfirmDialog(
            title = label + "会话",
            message = when (kind) {
                "delete" -> "会话历史会被永久删除，无法撤销。请核对记录名称和 ID。"
                "archive" -> "会话将从活跃列表移到归档，可随时恢复。"
                else -> "会话将返回活跃列表，可以继续任务。"
            },
            detail = "名称：$title\nID：$id",
            confirmLabel = "确认" + label,
            destructive = kind == "delete",
            onConfirm = {
                vm.rpc("threadLibraryAction",
                    when (kind) {
                        "delete" -> "thread/delete"
                        "archive" -> "thread/archive"
                        else -> "thread/unarchive"
                    },
                    JSONObject().put("threadId", id),
                    after = {
                        if (activeId == id && !running) vm.newThread()
                        load(cursor)
                        vm.refresh("threads", "thread/list", JSONObject().put("limit", 30))
                    })
                action = null
            },
            onDismiss = { action = null }
        )
    }
}
