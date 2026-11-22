package io.github.sy220284.codexandroid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Preserves the exact same drawer selection/search/scroll during width changes. */
internal class SidebarUiState(
    initialSearch: String = "",
    initialSearching: Boolean = false,
    initialMore: Boolean = false,
    initialIndex: Int = 0,
    initialOffset: Int = 0
) {
    var search by mutableStateOf(initialSearch)
    var searching by mutableStateOf(initialSearching)
    var more by mutableStateOf(initialMore)
    val scroll = LazyListState(initialIndex, initialOffset)

    companion object {
        val Saver = listSaver<SidebarUiState, Any>(
            save = {
                listOf(it.search, it.searching, it.more,
                    it.scroll.firstVisibleItemIndex, it.scroll.firstVisibleItemScrollOffset)
            },
            restore = {
                SidebarUiState(it[0] as String, it[1] as Boolean, it[2] as Boolean,
                    it[3] as Int, it[4] as Int)
            }
        )
    }
}

/**
 * 最近会话与工作台入口共享同一个抽屉。列表来自 thread/list，
 * 点击只提交真实会话 ID 给 ViewModel，不在界面伪造恢复成功。
 */
@Composable
internal fun SidebarSessions(
    vm: CodexViewModel,
    onOpenThread: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
    uiState: SidebarUiState? = null
) {
    val state = uiState ?: rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val liveExecutions by vm.threadExecutions.collectAsState()
    val activeId by vm.activeThread.collectAsState()
    val approvals by vm.bridge.prompts.collectAsState()
    val liveActivities by vm.threadActivities.collectAsState()
    val sidebarResponse = data["sidebarThreads"] ?: data["threads"]
    val all = records(sidebarResponse).filter { it.optString("id").isNotBlank() }
        .distinctBy { it.optString("id") }
    SidebarSessionsContent(
        all = all,
        modifier = modifier,
        onViewAll = onViewAll,
        hasResponse = sidebarResponse != null,
        connected = status == EngineStatus.Connected,
        loading = "sidebarThreads" in loading,
        hasError = errors["sidebarThreads"] != null,
        activeId = activeId,
        running = running,
        switching = switching || firstThreadStarting,
        liveExecutions = liveExecutions,
        onStopThread = vm::interruptThread,
        pendingApprovals = approvals.size,
        liveActivities = liveActivities,
        onRefresh = {
            vm.refresh("sidebarThreads", "thread/list", JSONObject().put("limit", 100))
        },
        onOpenThread = onOpenThread,
        uiState = state
    )
}

/** 与连接副作用分离：同一界面可以用固定协议数据做稳定的搜索与选择测试。 */
@Composable
internal fun SidebarSessionsContent(
    all: List<JSONObject>,
    modifier: Modifier = Modifier,
    onViewAll: () -> Unit = {},
    hasResponse: Boolean,
    connected: Boolean,
    loading: Boolean,
    hasError: Boolean,
    activeId: String?,
    running: Boolean,
    switching: Boolean,
    onRefresh: () -> Unit,
    onOpenThread: (String) -> Unit,
    pendingApprovals: Int = 0,
    liveActivities: Map<String, ThreadActivity> = emptyMap(),
    liveExecutions: Map<String, ThreadExecution> = emptyMap(),
    onStopThread: (String) -> Unit = {},
    uiState: SidebarUiState? = null
) {
    val state = uiState ?: rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val searching = state.searching
    val search = state.search
    val matches = all.filter { item ->
        search.isBlank() || listOf(
            item.optString("name"), item.optString("preview"), item.optString("cwd")
        ).any { it.contains(search.trim(), ignoreCase = true) }
    }

    val activeIds = (liveExecutions.keys + all.mapNotNull { entry ->
        val id = entry.optString("id")
        id.takeIf { it.isNotBlank() &&
            ThreadActivityPolicy.isActive(ThreadActivityPolicy.effective(
                entry, liveActivities[id], id in liveExecutions, 0)) }
    }).distinct()
    val backgroundActive = activeIds.count { it != activeId }
    val scroll = state.scroll
    LaunchedEffect(search, searching) {
        if (searching || search.isNotBlank()) scroll.scrollToItem(0)
    }
    Column(modifier.fillMaxWidth()) {
        if (running || pendingApprovals > 0 || activeIds.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)
                    .testTag("sidebar-task-status"),
                color = if (pendingApprovals > 0) Ink.warning.copy(alpha = .08f)
                    else Ink.jade.copy(alpha = .07f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp,
                    if (pendingApprovals > 0) Ink.warning.copy(alpha = .32f)
                    else Ink.jade.copy(alpha = .25f))
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        when {
                            pendingApprovals > 0 -> "待处理 ${pendingApprovals} 项审批 · ${activeIds.size} 条活跃会话"
                            activeIds.isNotEmpty() -> "${activeIds.size} 条会话正在执行或等待回应"
                            else -> "当前任务正在创建会话"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (pendingApprovals > 0) Ink.warning else Ink.jade
                    )
                    activeIds.take(4).forEach { id ->
                        val entry = all.firstOrNull { it.optString("id") == id }
                        val name = entry?.let {
                            PresentationPolicy.threadTitle(it.optString("name"), it.optString("preview"))
                        } ?: "会话 " + id.take(8)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onOpenThread(id) },
                                enabled = connected && !switching,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("sidebar-task-open-" + id)
                            ) {
                                Text(name, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                            }
                            if (liveExecutions[id]?.turnId != null) {
                                IconButton(
                                    onClick = { onStopThread(id) },
                                    enabled = connected,
                                    modifier = Modifier.size(48.dp)
                                        .testTag("sidebar-task-stop-" + id)
                                ) {
                                    Icon(Icons.Outlined.StopCircle,
                                        contentDescription = "停止会话 " + name)
                                }
                            }
                        }
                    }
                    if (activeIds.size > 4) Text(
                        "另有 ${activeIds.size - 4} 条活跃会话，可在下方列表切换",
                        color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("sidebar-thread-list"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        item(key = "session-list-heading") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最近会话", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                IconButton(
                    onClick = {
                        state.searching = !searching
                        if (!state.searching) state.search = ""
                    },
                    modifier = Modifier.size(48.dp).testTag("sidebar-search-toggle")
                ) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (searching) "关闭会话搜索"
                            else "搜索侧栏会话",
                        modifier = Modifier.size(19.dp), tint = Ink.muted)
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = connected && !loading,
                    modifier = Modifier.size(48.dp).testTag("sidebar-refresh")
                        .explainDisabled(!connected || loading,
                            if (!connected) "运行核心未连接，暂不能刷新会话" else "会话正在刷新")
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新侧栏会话",
                        modifier = Modifier.size(19.dp), tint = Ink.muted)
                }
            }
        }
        if (searching) item(key = "session-search") {
            OutlinedTextField(
                value = search, onValueChange = { state.search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    .testTag("sidebar-search"),
                placeholder = { Text("搜索当前会话") },
                leadingIcon = { Icon(Icons.Outlined.Search, null,
                    modifier = Modifier.size(17.dp)) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }
        item(key = "session-list-status") {
            when {
                !connected -> Text("连接内核后同步历史会话",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                loading && all.isEmpty() -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    Text("正在同步会话…", modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                }
                hasError -> Text("会话同步失败，点击上方刷新重试",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.red)
                all.isEmpty() && hasResponse -> Text(
                    "暂无历史会话，开始第一项任务后会显示在这里",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                all.isEmpty() -> Text("正在等待会话列表",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                matches.isEmpty() -> Text("没有匹配的会话",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
        }
        items(matches, key = { it.optString("id") }) { entry ->
            val id = entry.optString("id")
            SidebarSessionRow(
                item = entry,
                active = activeId == id,
                running = running && activeId == id,
                pendingApprovals = if (activeId == id) pendingApprovals else 0,
                activity = liveActivities[id] ?: ThreadActivityPolicy.fromThread(entry),
                enabled = connected && !switching,
                onClick = { onOpenThread(id) }
            )
        }
        item(key = "session-view-all") {
            TextButton(
                onClick = onViewAll,
                modifier = Modifier.fillMaxWidth().testTag("sidebar-view-all")
            ) {
                Text("查看全部会话", style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ChevronRight, null,
                    modifier = Modifier.size(17.dp))
            }
        }
    }
    }
}

@Composable
internal fun SidebarSessionRow(
    item: JSONObject,
    active: Boolean,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    pendingApprovals: Int = 0,
    activity: ThreadActivity = ThreadActivityPolicy.fromThread(item)
) {
    val id = item.optString("id")
    val actual = when {
        pendingApprovals > 0 -> ThreadActivity.WaitingApproval
        running -> ThreadActivity.Running
        else -> activity
    }
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    val surfaceColor by animateColorAsState(
        targetValue = if (active) Ink.coralSoft.copy(alpha = .105f) else Ink.canvas,
        animationSpec = tween(CodexMotion.duration(170, reduceMotion)),
        label = "sidebar-active-session"
    )
    Surface(
        modifier = Modifier.fillMaxWidth()
            .testTag("sidebar-thread-" + id)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = surfaceColor,
        border = if (active) BorderStroke(1.dp, Ink.coral.copy(alpha = .24f)) else null
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(if (active) Icons.Outlined.Chat else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null, tint = if (active) Ink.coral else Ink.muted,
                modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PresentationPolicy.threadTitle(item.optString("name"),
                    item.optString("preview")),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(when (actual) {
                    ThreadActivity.WaitingApproval ->
                        if (pendingApprovals > 0) "等待确认 ${pendingApprovals} 项" else "等待审批"
                    ThreadActivity.WaitingAnswer -> "等待回答"
                    ThreadActivity.Running -> "正在执行"
                    ThreadActivity.Failed -> "任务运行异常"
                    ThreadActivity.Idle -> PresentationPolicy.workspaceName(item.optString("cwd"))
                },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (actual) {
                        ThreadActivity.WaitingApproval, ThreadActivity.WaitingAnswer -> Ink.warning
                        ThreadActivity.Running -> Ink.jade
                        ThreadActivity.Failed -> Ink.red
                        else -> Ink.muted
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (active) Icon(Icons.Outlined.CheckCircle, contentDescription = "当前会话",
                tint = Ink.coral, modifier = Modifier.size(17.dp))
        }
    }
}
