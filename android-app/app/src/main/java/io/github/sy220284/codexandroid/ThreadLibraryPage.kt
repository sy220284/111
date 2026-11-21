package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    var fullText by rememberSaveable { mutableStateOf(false) }
    var fullSubmitted by rememberSaveable { mutableStateOf("") }
    var selectedSection by rememberSaveable { mutableStateOf("") }
    var showSectionEditor by remember { mutableStateOf(false) }
    var editingSection by remember { mutableStateOf(false) }
    var sectionDraft by rememberSaveable { mutableStateOf("") }
    var deleteSection by remember { mutableStateOf<JSONObject?>(null) }
    var threadToMove by remember { mutableStateOf<JSONObject?>(null) }
    val sectionResponse = data["threadSections"]
    val sections = records(sectionResponse)
    val currentSection = sections.firstOrNull { it.optString("id") == selectedSection }
    LaunchedEffect(Unit) {
        vm.refresh("threadSections", "threadSection/list", JSONObject().put("limit", 100))
    }
    var cursor by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<String?>>(emptyList()) }
    var rename by remember { mutableStateOf<JSONObject?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var action by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    // Bind the result to the exact server query: switching filters or pages
    // must never display results from a previous selection.
    val key = "library:" + archived + ":" + searchTerm + ":" + selectedSection + ":" + (cursor ?: "first")
    val response = data[key]
    val entries = records(response)
    val nextCursor = response?.optString("nextCursor")?.takeIf { it.isNotBlank() }
    val busy = "threadLibraryAction" in loading
    val fullKey = "fullSearch:" + fullSubmitted
    val fullResults = if (fullSubmitted.isNotBlank()) records(data[fullKey]) else emptyList()
    val fullPending = fullSubmitted.isNotBlank() && fullKey in loading

    fun load(pageCursor: String?) {
        vm.refresh(key, "thread/list",
            ThreadSectionContract.threadList(archived, searchTerm, pageCursor, selectedSection))
    }
    LaunchedEffect(archived, searchTerm, cursor, selectedSection, fullText) {
        if (!fullText) load(cursor)
    }

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
                        Text("按标题或全文检索真实会话记录", color = Ink.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        if (fullText && fullSubmitted.isNotBlank()) vm.refresh(fullKey, "thread/search",
                            JSONObject().put("searchTerm", fullSubmitted)) else load(cursor)
                    }, enabled = key !in loading && !fullPending) {
                        Icon(Icons.Outlined.Refresh, "刷新列表")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !fullText, onClick = {
                        fullText = false; fullSubmitted = ""; cursor = null; history = emptyList()
                    }, label = { Text("标题搜索") })
                    FilterChip(selected = fullText, onClick = {
                        fullText = true; fullSubmitted = ""; cursor = null; history = emptyList()
                    }, label = { Text("全文搜索") })
                }
                if (!fullText) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !archived, onClick = {
                        archived = false; cursor = null; history = emptyList()
                    }, label = { Text("活跃") })
                    FilterChip(selected = archived, onClick = {
                        archived = true; cursor = null; history = emptyList()
                    }, label = { Text("已归档") })
                }
                OutlinedTextField(searchText, { searchText = it },
                    label = { Text(if (fullText) "搜索历史正文" else "搜索会话标题") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            val submitted = searchText.trim()
                            if (fullText) {
                                fullSubmitted = submitted
                                if (submitted.isNotBlank()) vm.refresh("fullSearch:" + submitted,
                                    "thread/search", JSONObject().put("searchTerm", submitted))
                            } else if (searchTerm == submitted && cursor == null) load(null)
                            else {
                                searchTerm = submitted; cursor = null; history = emptyList()
                            }
                        }, enabled = key !in loading) {
                            Icon(Icons.Outlined.ArrowForward, "开始搜索")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(if (fullText) "全文检索由当前内核提供，搜索结果可直接恢复对应会话。" else
                    "标题搜索支持服务端游标分页。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                if (!fullText) {
                DividerLine()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("会话分组", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            sectionDraft = ""
                            editingSection = false
                            showSectionEditor = true
                        }, enabled = "sectionEdit" !in loading
                    ) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(17.dp))
                        Text("新建分组")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(selected = selectedSection.isEmpty(), onClick = {
                        selectedSection = ""; cursor = null; history = emptyList()
                    }, label = { Text("全部") })
                    FilterChip(
                        selected = selectedSection == ThreadSectionContract.UNGROUPED,
                        onClick = {
                            selectedSection = ThreadSectionContract.UNGROUPED
                            cursor = null; history = emptyList()
                        },
                        label = { Text("未分类") }
                    )
                    sections.forEach { section ->
                        val id = section.optString("id")
                        FilterChip(selected = selectedSection == id, onClick = {
                            selectedSection = id; cursor = null; history = emptyList()
                        }, label = { Text(section.optString("name")) })
                    }
                }
                currentSection?.let { section ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前分组：" + section.optString("name"), modifier = Modifier.weight(1f),
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            sectionDraft = section.optString("name")
                            editingSection = true
                            showSectionEditor = true
                        }) { Text("重命名") }
                        TextButton(onClick = { deleteSection = section }) {
                            Text("删除", color = Ink.red)
                        }
                    }
                }
                if ("threadSections" in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                errors["threadSections"]?.let {
                    Text("分组服务暂不可用：$it", color = Ink.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { vm.newThread(); navigate("chat") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !running) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(7.dp))
                    Text("新建任务")
                }
                }
            }
        }
        if (fullText) {
            if (fullPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            errors[fullKey]?.let { item { ErrorText(it) } }
            if (fullSubmitted.isBlank()) {
                item { CodexEmptyState("在历史正文中查找", "输入关键词并点击搜索，结果来自当前 Codex 内核。") }
            } else if (!fullPending && fullResults.isEmpty() && fullKey !in errors) {
                item { CodexEmptyState("没有全文检索结果", "尝试减少关键词或切回标题搜索。") }
            }
            items(fullResults, key = { "full-" + it.optString("id") }) { thread ->
                ThreadRow(thread, enabled = !running || activeId == thread.optString("id")) {
                    if (!running) vm.chooseThread(thread.optString("id"))
                    navigate("chat")
                }
            }
        } else {
        if (key in loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        errors[key]?.let { item { ErrorText(it) } }
        errors["threadLibraryAction"]?.let { item { ErrorText("会话操作失败：$it") } }
        errors["sectionEdit"]?.let { item { ErrorText("会话分组失败：$it") } }
        errors["sectionMove"]?.let { item { ErrorText("移动会话失败：$it") } }
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
                        color = Ink.canvas,
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
                            if (!archived) DropdownMenuItem(
                                text = { Text("移至分组") },
                                onClick = { menu = false; threadToMove = thread },
                                leadingIcon = { Icon(Icons.Outlined.Folder, null) }
                            )
                            DropdownMenuItem(text = { Text("永久删除") }, onClick = {
                                menu = false; action = "delete" to thread
                            }, leadingIcon = {
                                Icon(Icons.Outlined.DeleteOutline, null, tint = Ink.red)
                            })
                        }
                    }
                }
                val updatedAt = ThreadDisplayPolicy.updatedAt(
                    thread.opt("updatedAt"), thread.opt("createdAt"))
                if (updatedAt != null) {
                    Text("更新于 " + updatedAt,
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
                    OutlinedButton(onClick = { vm.chooseThread(id); navigate("chat") },
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
                    OutlinedButton(onClick = {
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
    }

    if (showSectionEditor) {
        AlertDialog(
            onDismissRequest = { showSectionEditor = false },
            title = { Text(if (editingSection) "重命名分组" else "新建会话分组") },
            text = {
                OutlinedTextField(sectionDraft, { sectionDraft = it },
                    label = { Text("分组名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                OutlinedButton(
                    enabled = sectionDraft.trim().isNotEmpty() &&
                        sectionDraft.trim().length <= 120 && "sectionEdit" !in loading,
                    onClick = {
                        val sectionId = currentSection?.optString("id")
                        if (editingSection && !sectionId.isNullOrBlank()) {
                            vm.rpc("sectionEdit", "threadSection/update",
                                ThreadSectionContract.rename(sectionId, sectionDraft),
                                after = { vm.refresh("threadSections", "threadSection/list") })
                        } else if (!editingSection) {
                            vm.rpc("sectionEdit", "threadSection/create",
                                ThreadSectionContract.create(sectionDraft),
                                after = { vm.refresh("threadSections", "threadSection/list") })
                        }
                        showSectionEditor = false
                    }
                ) { Text("保存分组") }
            },
            dismissButton = { TextButton(onClick = { showSectionEditor = false }) {
                Text("取消")
            } }
        )
    }
    deleteSection?.let { section ->
        CodexConfirmDialog(
            title = "删除会话分组",
            message = "将删除该分组记录。会话内容保留，分类关系由 Codex 处理。",
            detail = section.optString("name"),
            confirmLabel = "确认删除分组",
            destructive = true,
            onConfirm = {
                vm.rpc("sectionEdit", "threadSection/delete",
                    ThreadSectionContract.delete(section.optString("id")),
                    after = {
                        selectedSection = ""
                        cursor = null
                        history = emptyList()
                        vm.refresh("threadSections", "threadSection/list")
                    })
                deleteSection = null
            },
            onDismiss = { deleteSection = null }
        )
    }
    threadToMove?.let { thread ->
        CodexChoiceSheet(
            title = "移动会话到分组",
            description = "移动后仍可从全部会话找到此任务。",
            choices = listOf(CodexChoice("", "未分类", "放回全部会话"))
                + sections.map {
                    CodexChoice(it.optString("id"), it.optString("name"), "会话分组")
                },
            selectedId = thread.optJSONObject("section")?.optString("id") ?: "",
            onSelect = { target ->
                vm.rpc("sectionMove", "thread/section/move",
                    ThreadSectionContract.move(thread.optString("id"), target.ifBlank { null }),
                    after = { load(cursor) })
                threadToMove = null
            },
            onDismiss = { threadToMove = null }
        )
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
                OutlinedButton(onClick = {
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
