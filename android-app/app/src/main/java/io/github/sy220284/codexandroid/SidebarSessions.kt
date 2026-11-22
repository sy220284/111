package io.github.sy220284.codexandroid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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

/**
 * 最近会话与工作台入口共享同一个抽屉。列表来自 thread/list，
 * 点击只提交真实会话 ID 给 ViewModel，不在界面伪造恢复成功。
 */
@Composable
internal fun SidebarSessions(
    vm: CodexViewModel,
    onOpenThread: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeId by vm.activeThread.collectAsState()
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
        switching = switching,
        onRefresh = {
            vm.refresh("sidebarThreads", "thread/list", JSONObject().put("limit", 100))
        },
        onOpenThread = onOpenThread
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
    onOpenThread: (String) -> Unit
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val matches = all.filter { item ->
        search.isBlank() || listOf(
            item.optString("name"), item.optString("preview"), item.optString("cwd")
        ).any { it.contains(search.trim(), ignoreCase = true) }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("sidebar-thread-list"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("最近会话", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            IconButton(
                onClick = {
                    searching = !searching
                    if (!searching) search = ""
                },
                modifier = Modifier.size(48.dp).testTag("sidebar-search-toggle")
            ) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searching) "关闭会话搜索" else "搜索侧栏会话",
                    modifier = Modifier.size(19.dp), tint = Ink.muted)
            }
            IconButton(
                onClick = onRefresh,
                enabled = connected && !loading,
                modifier = Modifier.size(48.dp).testTag("sidebar-refresh")
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新侧栏会话",
                    modifier = Modifier.size(19.dp), tint = Ink.muted)
            }
        }
        CodexReveal(searching) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    .testTag("sidebar-search"),
                placeholder = { Text("搜索会话名称") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(17.dp)) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }
        when {
            !connected -> {
                Text("连接内核后同步最近会话",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
            loading && all.isEmpty() -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                Text("正在同步会话…", modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
            hasError -> {
                Text("会话同步失败，可点击刷新重试",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.red)
            }
            all.isEmpty() && hasResponse -> {
                Text("暂无会话，开始首个任务后会显示在这里",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
            all.isEmpty() -> {
                Text("等待内核返回会话列表",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
        }
        if (connected && all.isNotEmpty() && matches.isEmpty()) {
            Text("没有匹配的会话",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        }
        // 连接中保留已读取的标题，但禁用操作，防止误以为离线也能恢复。
        matches.forEach { item ->
            val id = item.optString("id")
            SidebarSessionRow(
                item = item,
                active = activeId == id,
                running = running && activeId == id,
                enabled = connected && !switching && (!running || activeId == id),
                onClick = { onOpenThread(id) }
            )
        }
        TextButton(
            onClick = onViewAll,
            modifier = Modifier.fillMaxWidth().testTag("sidebar-view-all")
        ) {
            Text("查看全部会话", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
internal fun SidebarSessionRow(
    item: JSONObject,
    active: Boolean,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val id = item.optString("id")
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    val surfaceColor by animateColorAsState(
        targetValue = if (active) Ink.copper.copy(alpha = .065f) else Ink.canvas,
        animationSpec = tween(CodexMotion.duration(170, reduceMotion)),
        label = "sidebar-active-session"
    )
    Surface(
        modifier = Modifier.fillMaxWidth()
            .testTag("sidebar-thread-" + id)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = surfaceColor,
        border = if (active) BorderStroke(1.dp, Ink.copperDim) else null
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(if (active) Icons.Outlined.Chat else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null, tint = if (active) Ink.copper else Ink.muted,
                modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PresentationPolicy.threadTitle(item.optString("name"),
                    item.optString("preview")),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (running) "正在执行" else
                    PresentationPolicy.workspaceName(item.optString("cwd")),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) Ink.jade else Ink.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (active) Icon(Icons.Outlined.CheckCircle, contentDescription = "当前会话",
                tint = Ink.copper, modifier = Modifier.size(17.dp))
        }
    }
}
