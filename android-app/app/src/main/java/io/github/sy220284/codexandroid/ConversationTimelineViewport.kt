package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Only the timeline viewport observes high-frequency presentation snapshots. */
@Composable
internal fun ConversationTimelineViewport(
    vm: CodexViewModel, thread: String?, switching: Boolean, busy: Boolean,
    firstThreadStarting: Boolean, compactWorkArea: Boolean, setupHint: ChatSetupHint?,
    navigate: (String) -> Unit, modifier: Modifier = Modifier
) {
    val frame by vm.conversationState.collectFrameState(thread)
    val contextLoading = switching || frame.threadId != thread
    val items = if (contextLoading) emptyList() else frame.timeline
    val olderTimelineCount by vm.olderTimelineCount.collectAsState()
    val olderCursor by vm.olderServerCursor.collectAsState()
    val loadingEarlier by vm.loadingEarlier.collectAsState()
    val groups = remember(items) { TimelineEventReducer.groups(items) }
    val olderPageVisible = (olderTimelineCount > 0 || olderCursor != null) && thread != null
    val replyProjection = remember(thread) { ProgressiveReplyProjection.Cache() }
    val rows = remember(groups, frame.running, frame.turnId, olderPageVisible, replyProjection) {
        replyProjection.rows(groups, frame.running, frame.turnId, olderPageVisible)
    }
    val answerVersions by vm.answerVersions.state.collectAsState()
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followTail by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    var lastKnownStepCount by remember { mutableIntStateOf(0) }
    var lastKnownTail by remember { mutableStateOf<TimelineItem?>(null) }
    var unreadSteps by remember { mutableIntStateOf(0) }
    var observedThread by remember { mutableStateOf<String?>(null) }
    var turnAction by remember(thread) { mutableStateOf<Pair<TimelineItem, MessageTurnAction>?>(null) }
    val mutatingHistory by vm.loading.collectProjection {
        "threadFork:$thread" in it || "threadRevert:$thread" in it
    }
    val familyBusy by vm.threadExecutions.collectProjection { executions -> thread?.let { id -> answerVersions.members(id).any { it != id && it in executions } } ?: false }
    val connected by vm.bridge.status.collectProjection { it == EngineStatus.Connected }
    val savingPermissions by vm.securitySaveBusy.collectAsState()
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val canSwitchVersion = connected && !busy && !contextLoading && thread != null && !loadingEarlier &&
        !firstThreadStarting && !mutatingHistory && !savingPermissions && !restarting
    val canChangeTurn = canSwitchVersion && !familyBusy
    val ownedVersion = thread?.let { answerVersions.ownedChoice(it) }
    val fallbackVersion = ownedVersion?.takeIf { version ->
        val turn = version.versions[version.index].turnId
        items.none { it.turnId == turn && it.kind in setOf("agent", "agentMessage") && it.phase != "commentary" }
    }
    val visibleGroups = rows.size + if (fallbackVersion != null && items.isNotEmpty()) 1 else 0
    val latestIndex = TimelineViewportPolicy.latestIndex(visibleGroups, olderPageVisible)

    // 被动内容增长不能被误判为用户离开底部；主动滚动时才暂停跟随。
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followTail = false
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { Triple(scroll.isScrollInProgress, scroll.canScrollForward, autoScrolling) }
            .collect { (scrolling, canForward, automatic) ->
                if (!scrolling && !canForward) {
                    followTail = true
                    unreadSteps = 0
                } else if (scrolling && canForward && !automatic) {
                    followTail = false
                }
            }
    }
    val hasOlderPage by rememberUpdatedState(olderPageVisible)
    val currentGroupCount by rememberUpdatedState(visibleGroups)
    val currentTail by rememberUpdatedState(items.lastOrNull())
    var previousViewportHeight by remember { mutableIntStateOf(0) }
    var previousOlderPage by remember { mutableStateOf(false) }
    LaunchedEffect(scroll, thread, contextLoading) {
        if (contextLoading) return@LaunchedEffect
        // One collector per conversation; a stream of deltas must not cancel and restart it.
        snapshotFlow { TimelineRenderTarget(currentGroupCount, currentTail, hasOlderPage,
            scroll.layoutInfo.viewportSize.height) }
            .collect { (count, tail, older, viewportHeight) ->
                val viewportChanged = viewportHeight != previousViewportHeight || older != previousOlderPage
                previousViewportHeight = viewportHeight
                previousOlderPage = older
                val threadChanged = observedThread != thread
                val collapsed = count < lastKnownStepCount
                val change = TimelineViewportPolicy.change(lastKnownStepCount, lastKnownTail, count, tail)
                observedThread = thread
                lastKnownStepCount = count
                lastKnownTail = tail
                if (threadChanged) {
                    followTail = true
                    unreadSteps = 0
                }
                val target = TimelineViewportPolicy.latestIndex(count, older)
                if (target >= 0 && followTail && !autoScrolling && (threadChanged || change.changed || viewportChanged || collapsed)) {
                    // Let the new list layout settle; a real drag takes precedence.
                    withFrameNanos { }
                    if (followTail && !autoScrolling) {
                        // A user scroll may cancel this child without cancelling the collector.
                        kotlinx.coroutines.coroutineScope {
                            launch {
                                autoScrolling = true
                                try {
                                    scroll.scrollToLatest(TimelineViewportPolicy.latestIndex(currentGroupCount, hasOlderPage), animate = false)
                                } finally {
                                    autoScrolling = false
                                }
                            }
                        }
                    }
                } else if (!followTail) {
                    unreadSteps += change.newSteps
                }
            }
    }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        if (contextLoading) {
            ConversationLoadingSkeleton(Modifier.fillMaxSize())
        } else if (items.isEmpty()) {
            val shortViewport = compactWorkArea || maxHeight < 345.dp
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = if (shortViewport) 6.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (shortViewport) 6.dp else 12.dp)
            ) {
                if (fallbackVersion != null) {
                    Text(if (busy) "正在生成此版本" else "此版本尚无完整回答", color = Ink.muted)
                    AnswerVersionSwitcher(fallbackVersion, if (canSwitchVersion) ({ target -> vm.chooseThread(target) }) else null, "empty-version")
                } else if (!compactWorkArea) {
                    Text("今天想做什么？",
                        style = if (shortViewport) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.headlineSmall,
                        color = Ink.parchment, textAlign = TextAlign.Center)
                    if (setupHint != null && !busy && !firstThreadStarting) {
                        ChatSetupGuide(setupHint, onNavigate = navigate)
                    } else if (!shortViewport) {
                        Text("提出问题，或交给 Codex 一项任务",
                            color = Ink.muted, style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        } else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (olderPageVisible) item(key = "timeline-older-pages") {
                CodexSecondaryButton(
                    onClick = { vm.loadEarlierTimeline() }, enabled = !loadingEarlier,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("timeline-load-older")
                ) {
                    Text(if (loadingEarlier) "正在加载…" else "加载更早记录")
                }
            }
            items(rows, key = { it.key }) { row ->
                val message = row.actionItem ?: row.group.singleOrNull()?.takeIf {
                    row.showActions && ProgressiveReplyProjection.answer(it)
                }
                val version = message?.let { item -> thread?.let { answerVersions.choice(it, item.turnId) } }
                TimelineActivityGroup(row.group, workspaceRoot = vm.workingDirectory,
                    onTurnAction = if (canChangeTurn) ({ item, action -> turnAction = item to action }) else null,
                    version = version, onVersion = if (canSwitchVersion) ({ target -> vm.chooseThread(target) }) else null,
                    showMessageActions = row.showActions, actionItem = row.actionItem,
                    forceActivity = row.forceActivity, note = row.note)
            }
            if (fallbackVersion != null) item(key = "answer-version-recovery") {
                Column { Text(if (busy) "正在生成此版本" else "此版本尚无完整回答", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    AnswerVersionSwitcher(fallbackVersion, if (canSwitchVersion) ({ target -> vm.chooseThread(target) }) else null, "recovery") }
            }
        }
        if (!contextLoading && latestIndex >= 0 && (!followTail || unreadSteps > 0)) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp).heightIn(min = 48.dp)
                    .testTag("timeline-return-latest")
                    .clickable(enabled = !autoScrolling) {
                        if (autoScrolling) return@clickable
                        scope.launch {
                            followTail = true
                            autoScrolling = true
                            try {
                                scroll.scrollToLatest(latestIndex, animate = !reducedMotion)
                                if (followTail) {
                                    scroll.scrollToLatest(TimelineViewportPolicy.latestIndex(currentGroupCount, hasOlderPage), animate = false)
                                    unreadSteps = 0
                                }
                            } finally {
                                autoScrolling = false
                            }
                        }
                    },
                shape = RoundedCornerShape(99.dp),
                color = Ink.canvas,
                border = BorderStroke(1.dp, Ink.copper),
                shadowElevation = 1.dp
            ) {
                Text(if (unreadSteps > 0) "↓ ${unreadSteps} 条新步骤" else "返回最新进度",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Ink.copper, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    turnAction?.let { (item, action) ->
        val clientId = items.firstOrNull { it.turnId == item.turnId && it.kind in setOf("user", "userMessage") }
            ?.metadata()?.optString("clientId")
        val baseline = remember(clientId, item.turnId) { vm.answerWorkspaces.hasBaseline(clientId) }
        CodexConfirmDialog(
            title = when (action) {
                MessageTurnAction.Fork -> "创建会话分支"
                MessageTurnAction.Retry -> "重新回答"
                MessageTurnAction.Revert -> "回退会话历史"
            },
            message = when (action) {
                MessageTurnAction.Fork -> "保留原会话，复制到此回合结束为止。"
                MessageTurnAction.Retry -> if (baseline) "在当前对话生成新回答，使用该回合执行前的文件快照。原回答和文件保留，可在消息下方切换版本。" else "该历史回合没有可核实的执行前文件快照。将复制当前文件到独立目录后重新执行，原回答和原文件保留。后续引导不自动重放。"
                MessageTurnAction.Revert -> "移除该回合及其之后的会话记录；已经修改的本机文件保持现状。"
            },
            confirmLabel = when (action) {
                MessageTurnAction.Fork -> "创建分支"
                MessageTurnAction.Retry -> "重新执行"
                MessageTurnAction.Revert -> "确认回退"
            },
            destructive = action == MessageTurnAction.Revert,
            onConfirm = {
                turnAction = null
                if (canChangeTurn && thread != null && vm.activeThread.value == thread) {
                    when (action) {
                        MessageTurnAction.Fork -> vm.conversationActions.fork(thread, item.turnId)
                        MessageTurnAction.Retry -> vm.conversationActions.retry(thread, item.turnId, allowCurrentFiles = !baseline)
                        MessageTurnAction.Revert -> vm.conversationActions.revert(thread, item.turnId)
                    }
                }
            },
            onDismiss = { turnAction = null })
    }
}

private data class TimelineRenderTarget(
    val count: Int, val tail: TimelineItem?, val older: Boolean, val viewportHeight: Int
)

/** 末项可能是长流式消息，滚动到末项后还要让消息尾部可见。 */
private suspend fun LazyListState.scrollToLatest(index: Int, animate: Boolean) {
    if (index < 0) return
    // Streaming changes often extend the already visible last item. Avoid jumping to its start.
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        if (animate) animateScrollToItem(index) else scrollToItem(index)
    }
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == index } ?: return
    val remaining = (last.offset + last.size - info.viewportEndOffset +
        info.afterContentPadding).coerceAtLeast(0)
    if (remaining > 0 && canScrollForward) {
        if (animate) animateScrollBy(remaining.toFloat()) else scrollBy(remaining.toFloat())
    }
}

/** Step count and kind are stable across text deltas; keep the composer out of these updates. */
@Composable
internal fun ConversationExecutionNotice(vm: CodexViewModel, stopping: Boolean) {
    val progress by vm.timeline.collectProjection { it.size to it.lastOrNull()?.kind.orEmpty() }
    CodexNotice(
        if (stopping) "已请求停止任务，正在等待内核确认。"
        else "执行中 · ${progress.first} 步 · " + PresentationPolicy.timelineKind(progress.second),
        NoticeTone.Info, Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
    )
}
