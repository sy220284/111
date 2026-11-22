package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
    onOpenThread: (String) -> Unit
) {
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeId by vm.activeThread.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val sidebarResponse = data["sidebarThreads"] ?: data["threads"]
    val all = records(sidebarResponse).filter { it.optString("id").isNotBlank() }
        .distinctBy { it.optString("id") }
    val matches = all.filter { item ->
        search.isBlank() || listOf(
            item.optString("name"), item.optString("preview"), item.optString("cwd")
        ).any { it.contains(search.trim(), ignoreCase = true) }
    }.take(6)
    val connected = status == EngineStatus.Connected

    Column(
        modifier = Modifier.fillMaxWidth().testTag("sidebar-thread-list"),
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
                modifier = Modifier.size(42.dp).testTag("sidebar-search-toggle")
            ) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searching) "关闭会话搜索" else "搜索侧栏会话",
                    modifier = Modifier.size(19.dp), tint = Ink.muted)
            }
            IconButton(
                onClick = {
                    vm.refresh("sidebarThreads", "thread/list", JSONObject().put("limit", 30))
                },
                enabled = connected && "sidebarThreads" !in loading,
                modifier = Modifier.size(42.dp).testTag("sidebar-refresh")
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
            "sidebarThreads" in loading && all.isEmpty() -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                Text("正在同步会话…", modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
            errors["sidebarThreads"] != null -> {
                Text("会话同步失败，可点击刷新重试",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = Ink.red)
            }
            all.isEmpty() && sidebarResponse != null -> {
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
        if (all.size > 6 && search.isBlank()) {
            Text("显示最近 6 条，完整记录在“会话历史”中",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = Ink.muted)
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
    Surface(
        modifier = Modifier.fillMaxWidth()
            .testTag("sidebar-thread-" + id)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (active) Ink.copper.copy(alpha = .065f) else Ink.canvas,
        border = BorderStroke(1.dp,
            if (active) Ink.copperDim else Ink.border.copy(alpha = .54f))
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
