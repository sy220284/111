package io.github.sy220284.codexandroid

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Preserve the drawer's scroll position across navigation and recreation. */
internal class SidebarUiState(initialIndex: Int = 0, initialOffset: Int = 0) {
    val scroll = LazyListState(initialIndex, initialOffset)

    companion object {
        val Saver = listSaver<SidebarUiState, Int>(
            save = { listOf(it.scroll.firstVisibleItemIndex, it.scroll.firstVisibleItemScrollOffset) },
            restore = { SidebarUiState(it[0], it[1]) }
        )
    }
}

/** 侧栏直接显示真实会话；搜索和管理集中在独立会话资料库。 */
@Composable
internal fun SidebarSessions(
    vm: CodexViewModel,
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
    uiState: SidebarUiState? = null
) {
    val data by vm.data.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val liveExecutions by vm.threadExecutions.collectAsState()
    val activeId by vm.activeThread.collectAsState()
    val approvals by vm.bridge.prompts.collectAsState()
    val liveActivities by vm.threadActivities.collectAsState()
    SidebarSessionsContent(
        all = records(data["sidebarThreads"] ?: data["threads"]),
        modifier = modifier,
        connected = status == EngineStatus.Connected,
        activeId = activeId,
        running = running,
        switching = switching || firstThreadStarting,
        liveExecutions = liveExecutions,
        onStopThread = vm::interruptThread,
        pendingApprovals = approvals.size,
        liveActivities = liveActivities,
        onOpenThread = onOpenThread,
        uiState = uiState
    )
}

/** 仅渲染会话行，空列表不占用额外提示空间；同一会话只显示一次。 */
@Composable
internal fun SidebarSessionsContent(
    all: List<JSONObject>,
    modifier: Modifier = Modifier,
    connected: Boolean,
    activeId: String?,
    running: Boolean,
    switching: Boolean,
    onOpenThread: (String) -> Unit,
    pendingApprovals: Int = 0,
    liveActivities: Map<String, ThreadActivity> = emptyMap(),
    liveExecutions: Map<String, ThreadExecution> = emptyMap(),
    onStopThread: (String) -> Unit = {},
    uiState: SidebarUiState? = null
) {
    val state = uiState ?: rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val sessions = all.filter { it.optString("id").isNotBlank() }
        .distinctBy { it.optString("id") }
    LazyColumn(
        state = state.scroll,
        modifier = modifier.fillMaxWidth().testTag("sidebar-thread-list"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(sessions, key = { it.optString("id") }) { entry ->
            val id = entry.optString("id")
            SidebarSessionRow(
                item = entry,
                active = activeId == id,
                running = running && activeId == id,
                pendingApprovals = if (activeId == id) pendingApprovals else 0,
                activity = liveActivities[id] ?: ThreadActivityPolicy.effective(
                    entry, null, id in liveExecutions, 0),
                enabled = connected && !switching,
                onClick = { onOpenThread(id) },
                onStop = if (liveExecutions[id]?.turnId != null) {
                    { onStopThread(id) }
                } else null,
                stopEnabled = connected
            )
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
    activity: ThreadActivity = ThreadActivityPolicy.fromThread(item),
    onStop: (() -> Unit)? = null,
    stopEnabled: Boolean = enabled
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
            .codexPressable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = surfaceColor,
        border = if (active) BorderStroke(1.dp, Ink.coral.copy(alpha = .24f)) else null
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CodexIcon(CodexGlyph.Thread,
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
            if (onStop != null) CodexIconAction(
                onClick = onStop, enabled = stopEnabled,
                modifier = Modifier.size(48.dp).testTag("sidebar-task-stop-" + id)
            ) {
                CodexIcon(CodexGlyph.Stop, contentDescription = "停止会话 " +
                    PresentationPolicy.threadTitle(item.optString("name"), item.optString("preview")))
            }
            if (active) CodexIcon(CodexGlyph.TaskComplete, contentDescription = "当前会话",
                tint = Ink.coral, modifier = Modifier.size(17.dp))
        }
    }
}
