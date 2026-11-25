package io.github.sy220284.codexandroid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

internal class SidebarUiState(initialIndex: Int = 0, initialOffset: Int = 0) {
    var searching by mutableStateOf(false)
    var search by mutableStateOf("")
    var fullText by mutableStateOf(false)
    var archived by mutableStateOf(false)
    var projectOnly by mutableStateOf(false)
    val scroll = LazyListState(initialIndex, initialOffset)
    fun resetFilter() { search = ""; fullText = false; archived = false; projectOnly = false }
    companion object {
        val Saver = listSaver<SidebarUiState, Int>(
            save = { listOf(it.scroll.firstVisibleItemIndex, it.scroll.firstVisibleItemScrollOffset) },
            restore = { SidebarUiState(it[0], it[1]) })
    }
}

/** The single session surface: flat list, on-demand search and archive management. */
@Composable
internal fun SidebarSessions(
    vm: CodexViewModel, onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier, uiState: SidebarUiState? = null,
    continuationLabel: String? = null
) {
    val state = uiState ?: rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val context = LocalContext.current
    var recentTitle by remember { mutableStateOf(RecentThreadSearches.load(context, false)) }
    var recentFull by remember { mutableStateOf(RecentThreadSearches.load(context, true)) }
    var historyError by remember { mutableStateOf("") }
    val data by vm.data.collectEntries("sidebarThreads", "archivedThreads")
    val loading by vm.loading.collectProjection { it.intersect(setOf("sidebarThreads", "archivedThreads", "threadLibraryAction", "threadLibraryBulk")) }
    val errors by vm.errors.collectEntries("sidebarThreads", "archivedThreads", "threadLibraryAction", "threadLibraryBulk")
    val status by vm.bridge.status.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val starting by vm.firstThreadStarting.collectAsState()
    val activeId by vm.activeThread.collectAsState()
    val executions by vm.threadExecutions.collectAsState()
    val activities by vm.threadActivities.collectAsState()
    val prompts by vm.bridge.prompts.collectAsState()
    val workspace by vm.activeWorkspace.collectAsState()
    val pins by vm.sessions.pins.collectAsState()
    val receipts by vm.sessions.receipts.collectAsState()
    val searchResult by vm.sessions.searchResult.collectAsState()
    val searchBusy by vm.sessions.searching.collectAsState()
    val searchError by vm.sessions.searchError.collectAsState()
    val completed by vm.bulkCompletedThreadIds.collectAsState()
    val connected = status == EngineStatus.Connected
    val busy = switching || starting || "threadLibraryAction" in loading || "threadLibraryBulk" in loading
    val answerMethods = remember { setOf("item/tool/requestUserInput", "mcpServer/elicitation/request") }
    val approvals = remember(prompts) {
        prompts.filter { it.method !in answerMethods }
            .groupingBy { it.params.optString("threadId") }.eachCount()
    }
    val questions = remember(prompts) {
        prompts.filter { it.method in answerMethods }.map { it.params.optString("threadId") }.toSet()
    }
    val currentActivities = remember(activities, questions, approvals) {
        activities + questions.filter { (approvals[it] ?: 0) == 0 }
            .associateWith { ThreadActivity.WaitingAnswer }
    }
    var section by remember { mutableStateOf<AndroidThreadSection?>(null) }
    var sectionRows by remember { mutableStateOf<List<JSONObject>?>(null) }
    var sectionError by remember { mutableStateOf("") }
    var sectionRevision by remember { mutableIntStateOf(0) }
    var managingSections by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Map<String, Set<String>>?>(null) }
    val response = data[if (state.archived) "archivedThreads" else "sidebarThreads"]
    val rawRows = remember(section, state.fullText, response, sectionRows) {
        if (section == null || state.fullText) records(response) else sectionRows.orEmpty()
    }
    LaunchedEffect(section, state.archived, connected, sectionRevision) {
        val selected = section
        sectionRows = null
        sectionError = ""
        if (selected != null && connected) {
            try {
                sectionRows = capabilityPages { cursor ->
                    vm.capabilityRequest("thread/list",
                        ThreadSectionContract.threadList(state.archived, "", cursor, selected.id))
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
                sectionError = capabilityError(cancel)
            } catch (failure: Exception) {
                sectionError = capabilityError(failure)
            }
        }
    }
    LaunchedEffect(vm) {
        vm.bridge.events.collect { event ->
            if (section != null && event.optString("method") in setOf(
                    "thread/section/changed", "thread/archived", "thread/unarchived", "thread/closed")) {
                sectionRevision++
            }
        }
    }
    val answerVersions by vm.answerVersions.state.collectAsState()
    val query = state.search
    val archived = state.archived
    val fullText = state.fullText
    val matching = remember(rawRows, answerVersions, fullText, archived, query, searchResult) {
        if (fullText && query.isNotBlank())
            searchResult?.takeIf { it.first == (archived to query.trim()) }?.second.orEmpty()
        else if (fullText) emptyList() else {
            val matchingRoots = rawRows.filter { SessionListPolicy.titleMatches(it, query) }
                .mapTo(hashSetOf()) { answerVersions.root(SessionListPolicy.id(it)) }
            rawRows.filter { answerVersions.root(SessionListPolicy.id(it)) in matchingRoots }
        }
    }
    val remoteState = vm.conversationState.collectProjection { it.remote }
    val remote = remoteState.value
    val rows = remember(rawRows, matching, answerVersions, state.projectOnly, workspace, remote) {
        answerVersions.rows(rawRows, matching).filter { row ->
            if (!state.projectOnly) true
            else {
                val bound = vm.remoteBindings.forThread(SessionListPolicy.id(row))
                if (remote != null) bound != null && bound.id == remote.id && bound.cwd == remote.cwd
                else bound == null && row.optString("cwd") == workspace
            }
        }
    }
    val visiblePins = remember(pins, rows, answerVersions) { rows.map(SessionListPolicy::id).filter { answerVersions.root(it) in pins }.toSet() }
    var selection by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    var rename by remember { mutableStateOf<JSONObject?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    LaunchedEffect(state.archived, connected) { vm.sessions.showArchive(state.archived && connected) }
    DisposableEffect(vm) { onDispose { vm.sessions.showArchive(false) } }
    LaunchedEffect(state.searching, state.fullText, state.archived, state.search, connected, section?.id, state.projectOnly) {
        if (state.searching && state.fullText) {
            delay(250)
            vm.sessions.search(state.search, state.archived)
        } else vm.sessions.search("", state.archived)
        if (state.searching) state.scroll.scrollToItem(0)
        selection = false
        selectedIds = emptySet()
    }
    LaunchedEffect(completed) { selectedIds = selectedIds - completed }
    fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun canManage(row: JSONObject): Boolean {
        val id = SessionListPolicy.id(row)
        return connected && !busy && answerVersions.members(id).none { it in executions } &&
            !ThreadActivityPolicy.isActive(currentActivities[id] ?: ThreadActivityPolicy.fromThread(row))
    }
    fun moveRows(ids: Collection<String>) {
        moving = ids.associateWith { answerVersions.members(it).toSet() }
    }
    Column(modifier.fillMaxSize()) {
        if (section != null && !state.fullText && !selection) Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
        ) {
            Text(section?.name.orEmpty(), modifier = Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.labelMedium, color = Ink.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            CodexTextAction(onClick = { section = null }) { Text("清除分组筛选") }
        }
        if (section != null && !state.fullText && connected) {
            if (sectionError.isNotBlank()) CodexNotice(sectionError, NoticeTone.Error,
                actionLabel = "重试", onAction = { sectionRevision++ })
            else if (sectionRows == null) AtlasOperationProgress("正在读取分组会话")
            else if (rows.isEmpty()) Text("当前分组没有匹配会话", color = Ink.muted)
        }
        continuationLabel?.let { Text("选择会话后继续：$it", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(8.dp)) }
        if (state.searching) {
            ClipboardQueryAction("使用剪贴板检索会话", state.search, onQuery = { term ->
                state.search = term
                try {
                    if (state.fullText) recentFull = RecentThreadSearches.record(context, true, term)
                    else recentTitle = RecentThreadSearches.record(context, false, term)
                    historyError = ""
                } catch (failure: Exception) { historyError = failure.message ?: "搜索记录保存失败" }
            }, onClear = { state.search = "" }, tag = "sidebar-clipboard-query")
            AtlasCompactChoice(if (state.fullText) "full" else "title",
                listOf("title" to "标题搜索", "full" to "全文搜索"),
                onSelect = { state.fullText = it == "full" })
            AtlasCompactChoice(if (state.archived) "archived" else "active",
                listOf("active" to "会话", "archived" to "已归档"),
                onSelect = { state.archived = it == "archived" })
            AtlasCompactChoice(if (state.projectOnly) "project" else "all",
                listOf("all" to "全部项目", "project" to "当前项目"),
                onSelect = { state.projectOnly = it == "project" })
            val recent = if (state.fullText) recentFull else recentTitle
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近搜索", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    CodexTextAction(onClick = {
                        try {
                            val cleared = RecentThreadSearches.clear(context, state.fullText)
                            if (state.fullText) recentFull = cleared else recentTitle = cleared
                            historyError = ""
                        } catch (failure: Exception) { historyError = failure.message ?: "清除记录失败" }
                    }) { Text("清除记录") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    recent.forEach { term ->
                        CodexFilterChip(selected = state.search == term, onClick = { state.search = term },
                            label = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            if (historyError.isNotBlank()) ErrorText(historyError)
            if (searchBusy && state.fullText && rows.isEmpty()) AtlasOperationProgress("正在搜索正文")
            else if (state.search.isNotBlank() && rows.isEmpty() &&
                (!state.fullText || searchResult?.first == (state.archived to state.search.trim())))
                Text("未找到匹配会话", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
            if (state.fullText && searchError.isNotBlank())
                CodexNotice(PresentationPolicy.readableError(searchError), NoticeTone.Error,
                    actionLabel = "重试", onAction = { vm.sessions.search(state.search, state.archived) })
        }
        errors["sidebarThreads"]?.let { error ->
            CodexNotice(PresentationPolicy.readableError(error), NoticeTone.Warning,
                actionLabel = "重试", onAction = vm::syncRecentThreads)
        }
        if (state.archived && response == null && "sidebarThreads" in loading)
            AtlasOperationProgress("正在读取归档会话")
        SidebarSessionsContent(all = rows, modifier = Modifier.weight(1f).fillMaxWidth(),
            connected = connected, activeId = activeId, running = running, switching = busy,
            onOpenThread = { id ->
                val row = rows.firstOrNull { SessionListPolicy.id(it) == id }
                when {
                    selection -> if (row != null && canManage(row)) toggle(id)
                    state.archived -> menu = row
                    else -> onOpenThread(id)
                }
            },
            liveActivities = currentActivities, liveExecutions = executions,
            onStopThread = vm::interruptThread, uiState = state, pinned = visiblePins,
            readStates = receipts, approvalsByThread = approvals, activeWorkspace = workspace,
            selectedIds = selectedIds, selecting = selection,
            onLongPress = { row -> if (!selection) menu = row else if (canManage(row)) toggle(SessionListPolicy.id(row)) })
        if (selection) ThreadBulkToolbar(selectedCount = selectedIds.size, archived = state.archived,
            enabled = connected && !busy,
            onSelectAll = { selectedIds = rows.filter(::canManage).map(SessionListPolicy::id).toSet() },
            onClear = { selectedIds = emptySet() },
            onFinish = { selectedIds = emptySet(); selection = false },
            onAction = { kind ->
                when (kind) {
                    "move" -> moveRows(selectedIds)
                    "pin", "unpin" -> if (vm.sessions.setPinned(
                            selectedIds.map(answerVersions::root).toSet(), kind == "pin")) {
                        selectedIds = emptySet()
                        selection = false
                    }
                    else -> confirm = kind to selectedIds.toList()
                }
            })
    }
    if (managingSections || moving != null) {
        ThreadSectionSheet(vm, section, moving,
            onFilter = { section = it; sectionRevision++; managingSections = false },
            onMoved = { completedMoves ->
                selectedIds -= completedMoves
                if (selectedIds.isEmpty()) selection = false
                sectionRevision++
                vm.syncRecentThreads()
            },
            onDismiss = { managingSections = false; moving = null })

    }
    menu?.let { row ->
        val id = SessionListPolicy.id(row)
        val canEdit = canManage(row)
        val read = receipts[id]
        CodexActionDialog(title = SessionListPolicy.title(row), onDismiss = { menu = null },
            actions = buildList {
                add(CodexSheetAction(if (answerVersions.root(id) in pins) "取消置顶" else "置顶会话", CodexGlyph.ArrowUpward,
                    onClick = { vm.sessions.togglePin(answerVersions.root(id)) }, testTag = "session-pin"))
                if (read != null) add(CodexSheetAction(
                    if (SessionListPolicy.unread(read)) "标记为已读" else "标记为未读",
                    CodexGlyph.RadioButtonChecked, onClick = { vm.sessions.markRead(id, !SessionListPolicy.unread(read)) },
                    enabled = connected, testTag = "session-read-state"))
                add(CodexSheetAction("重命名", CodexGlyph.Edit, enabled = canEdit,
                    onClick = { name = SessionListPolicy.title(row); rename = row }, testTag = "session-rename"))
                if (id in executions) add(CodexSheetAction("停止任务", CodexGlyph.Stop,
                    enabled = connected && executions[id]?.turnId != null, onClick = { vm.interruptThread(id) }))
                add(CodexSheetAction("批量管理", CodexGlyph.SelectAll, enabled = canEdit && !state.fullText,
                    onClick = { selection = true; selectedIds = setOf(id) }, testTag = "session-multi-select"))
                add(CodexSheetAction(if (state.archived) "恢复会话" else "归档会话",
                    if (state.archived) CodexGlyph.Unarchive else CodexGlyph.Archive, enabled = canEdit,
                    onClick = { confirm = (if (state.archived) "unarchive" else "archive") to listOf(id) },
                    testTag = "session-archive"))
                add(CodexSheetAction("管理分组", CodexGlyph.Folder, enabled = connected && !busy && !state.fullText,
                    onClick = { managingSections = true }, testTag = "session-manage-sections"))
                add(CodexSheetAction("移动会话", CodexGlyph.Folder, enabled = canEdit,
                    onClick = { moveRows(listOf(id)) }, testTag = "session-move"))
                add(CodexSheetAction("删除会话", CodexGlyph.DeleteOutline, enabled = canEdit,
                    destructive = true, onClick = { confirm = "delete" to listOf(id) }, testTag = "session-delete"))
            })
    }
    rename?.let { row ->
        CodexDialogFrame(onDismissRequest = { rename = null }, title = { Text("重命名会话") },
            text = { ActionReplyRow(name, { name = it }, label = { Text("会话名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), directText = true) },
            confirmButton = { CodexSecondaryButton(onClick = {
                vm.rpc("threadLibraryAction", "thread/name/set", JSONObject()
                    .put("threadId", answerVersions.root(SessionListPolicy.id(row))).put("name", name.trim()))
                rename = null
            }, enabled = name.isNotBlank() && canManage(row)) { Text("保存名称") } },
            dismissButton = { CodexTextAction(onClick = { rename = null }) { Text("取消") } })
    }
    confirm?.let { (kind, ids) ->
        val label = ThreadBulkActionPolicy.display(kind)
        CodexConfirmDialog(title = label + "会话",
            message = if (kind == "delete") "将永久删除所选会话及全部回答版本的历史记录，请核对范围。"
                else "将" + label + "所选会话。",
            detail = "已选择 ${ids.size} 条会话",
            confirmLabel = "确认" + label, destructive = kind == "delete",
            onConfirm = {
                vm.bulkThreadAction(kind, ids) { sectionRevision++; vm.syncRecentThreads() }
                confirm = null
            }, onDismiss = { confirm = null })
    }
}

@Composable
internal fun SidebarSessionsContent(
    all: List<JSONObject>, modifier: Modifier = Modifier, connected: Boolean,
    activeId: String?, running: Boolean, switching: Boolean, onOpenThread: (String) -> Unit,
    pendingApprovals: Int = 0, liveActivities: Map<String, ThreadActivity> = emptyMap(),
    liveExecutions: Map<String, ThreadExecution> = emptyMap(), onStopThread: (String) -> Unit = {},
    uiState: SidebarUiState? = null, pinned: Set<String> = emptySet(),
    readStates: Map<String, JSONObject> = emptyMap(), approvalsByThread: Map<String, Int> = emptyMap(),
    activeWorkspace: String = "", selectedIds: Set<String> = emptySet(), selecting: Boolean = false,
    onLongPress: ((JSONObject) -> Unit)? = null
) {
    val state = uiState ?: rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val ordered = remember(all, pinned) { SessionListPolicy.ordered(all, pinned) }
    LazyColumn(state = state.scroll, modifier = modifier.fillMaxWidth().testTag("sidebar-thread-list"),
        verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(ordered, key = SessionListPolicy::id) { row ->
            val id = SessionListPolicy.id(row)
            val count = approvalsByThread[id] ?: if (id == activeId) pendingApprovals else 0
            val activity = SessionListPolicy.activity(row, liveActivities[id],
                id in liveExecutions || running && id == activeId, count)
            SidebarSessionRow(item = row, active = id == activeId, running = false,
                enabled = connected && !switching, onClick = { onOpenThread(id) },
                pendingApprovals = count, activity = activity,
                pinned = id in pinned, unread = SessionListPolicy.unread(readStates[id]),
                crossProject = activeWorkspace.isNotBlank() && row.optString("cwd").isNotBlank() &&
                    row.optString("cwd") != activeWorkspace,
                selecting = selecting, checked = id in selectedIds,
                onLongPress = onLongPress?.let { action -> { action(row) } },
                onStop = if (liveExecutions[id]?.turnId != null && !selecting) { { onStopThread(id) } } else null,
                stopEnabled = connected)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SidebarSessionRow(
    item: JSONObject, active: Boolean, running: Boolean, enabled: Boolean, onClick: () -> Unit,
    pendingApprovals: Int = 0, activity: ThreadActivity = ThreadActivityPolicy.fromThread(item),
    pinned: Boolean = false, unread: Boolean = false, crossProject: Boolean = false,
    selecting: Boolean = false, checked: Boolean = false, onLongPress: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null, stopEnabled: Boolean = enabled
) {
    val id = SessionListPolicy.id(item)
    val actual = SessionListPolicy.activity(item, activity, running, pendingApprovals)
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val color by animateColorAsState(if (active || checked) Ink.elevated else Ink.canvas,
        tween(CodexMotion.duration(CodexMotion.selection, reduced)), label = "session-selection")
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .testTag("sidebar-thread-" + id).semantics { selected = active || checked }
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongPress,
            onLongClickLabel = "管理会话"), shape = RoundedCornerShape(10.dp), color = color) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (selecting) CodexCheckBox(checked = checked, enabled = enabled, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(SessionListPolicy.title(item), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stateLabel = when (actual) {
                    ThreadActivity.WaitingApproval -> if (pendingApprovals > 0) "等待确认 ${pendingApprovals} 项" else "等待审批"
                    ThreadActivity.WaitingAnswer -> "等待回答"
                    ThreadActivity.Running -> "正在执行"
                    ThreadActivity.Failed -> "任务运行异常"
                    ThreadActivity.Idle -> if (crossProject) {
                        val path = item.optString("cwd")
                        val leaf = PresentationPolicy.meaningful(path.trimEnd('/').substringAfterLast('/'))
                        if (leaf == "projects" || leaf.isBlank()) PresentationPolicy.workspaceName(path) else leaf
                    } else ""
                }
                if (stateLabel.isNotBlank()) Text(stateLabel, style = MaterialTheme.typography.labelSmall,
                    color = when (actual) {
                        ThreadActivity.Running -> Ink.jade
                        ThreadActivity.WaitingApproval, ThreadActivity.WaitingAnswer -> Ink.warning
                        ThreadActivity.Failed -> Ink.red
                        else -> Ink.muted
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PresentationPolicy.meaningful(item.optString("searchSnippet")).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pinned) CodexIcon(CodexGlyph.ArrowUpward, "已置顶", Modifier.size(15.dp), Ink.muted)
            if (unread) CodexIcon(CodexGlyph.RadioButtonChecked, "未读", Modifier.size(12.dp), Ink.coral)
            if (onStop != null) CodexIconAction(onClick = onStop, enabled = stopEnabled,
                modifier = Modifier.size(48.dp).testTag("sidebar-task-stop-" + id)) {
                CodexIcon(CodexGlyph.Stop, "停止会话")
            }
        }
    }
}
