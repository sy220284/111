package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun ThreadGoalPage(vm: CodexViewModel, inSheet: Boolean = false) {
    val active by vm.activeThread.collectAsState()
    val key = "goal:" + (active ?: "none")
    val data by vm.data.collectEntries(key)
    val errors by vm.errors.collectEntries(key, "goalWrite")
    val loading by vm.loading.collectProjection { it.intersect(setOf(key, "goalWrite")) }
    val goal = data[key]?.optJSONObject("goal")
    val panelDraft by vm.taskPanelDrafts.collectProjection { it[active] ?: TaskPanelDraft() }
    val objective = panelDraft.goalText
    val status = panelDraft.goalStatus
    val budget = panelDraft.goalBudget
    var confirmClear by remember(active) { mutableStateOf(false) }
    var showBudgetEditor by remember(active) { mutableStateOf(false) }
    var budgetInput by remember(active) { mutableStateOf("") }
    val budgetEdited = panelDraft.goalBudgetEdited
    val safeBudget = budget.isBlank() || (budget.toLongOrNull()?.let { it > 0 } == true)
    val safeBudgetInput = budgetInput.isBlank() ||
        (budgetInput.toLongOrNull()?.let { it > 0 } == true)

    LaunchedEffect(active) {
        if (active != null) vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active))
    }
    Column(Modifier.fillMaxSize()) {
    ScreenColumn(modifier = Modifier.weight(1f)) {
        if (!inSheet) SectionIntro("任务目标", "设定希望达成的结果，并查看当前任务进度。")
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("请先从历史记录或任务对话打开一个会话", style = MaterialTheme.typography.titleMedium)
                Text("选中会话后即可读取、设定和更新该会话目标。", color = Ink.muted)
            }
            return@ScreenColumn
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Flag, null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Text("当前目标", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                CodexIconAction(onClick = { vm.refresh(key, "thread/goal/get",
                    JSONObject().put("threadId", active)) }) {
                    CodexIcon(CodexGlyph.Retry, "刷新")
                }
            }
            if (key in loading) AtlasOperationProgress("正在读取任务目标")
            if (goal == null && key !in loading && key !in errors && data[key] != null) {
                Text("当前会话尚未设定目标", color = Ink.muted)
            } else if (goal != null) {
                Text(goal.optString("objective"), style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatusPill(PresentationPolicy.goalStatus(goal.optString("status")),
                        goal.optString("status") == "active")
                }
                Text("已使用 Token：" + goal.optLong("tokensUsed") + " · 执行秒数：" +
                    goal.optLong("timeUsedSeconds"), color = Ink.muted)
                CodexSecondaryButton(onClick = {
                    vm.editTaskPanelDraft(active) {
                        it.copy(
                            goalText = goal.optString("objective"),
                            goalStatus = goal.optString("status").ifBlank { "active" },
                            goalBudget = goal.opt("tokenBudget")
                                ?.takeIf { value -> value != JSONObject.NULL }?.toString().orEmpty(),
                            goalBudgetEdited = false
                        )
                    }
                }) { Text("编辑当前目标") }
                CodexTextAction(onClick = { confirmClear = true }) { Text("清除目标") }
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text(if (goal == null) "设置新目标" else "更新目标", style = MaterialTheme.typography.titleMedium)
            ActionReplyRow(objective, { text ->
                vm.editTaskPanelDraft(active) { it.copy(goalText = text) }
            }, modifier = Modifier.fillMaxWidth(),
                label = { Text("目标说明") }, placeholder = { Text("写明期望结果与验收标准") },
                minLines = 3, maxLines = 7, directText = true)
            Text("执行状态", style = MaterialTheme.typography.titleMedium)
            AtlasStatusSelector(
                selected = status,
                options = listOf(
                    AtlasStatusOption("active", "进行中", StatusTone.Info),
                    AtlasStatusOption("paused", "已暂停", StatusTone.Neutral),
                    AtlasStatusOption("blocked", "遇到阻碍", StatusTone.Warning),
                    AtlasStatusOption("complete", "已完成", StatusTone.Success)
                ),
                onSelect = { value ->
                    vm.editTaskPanelDraft(active) { it.copy(goalStatus = value) }
                },
                modifier = Modifier.testTag("goal-status-selector")
            )
            val currentBudget = goal?.opt("tokenBudget")
                ?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            AtlasSettingsActionRow(
                title = "执行额度",
                detail = when {
                    budgetEdited && budget.isBlank() -> "待保存：清除 Token 上限"
                    budgetEdited -> "待保存：Token 上限 $budget"
                    currentBudget.isNotBlank() -> "当前 Token 上限：$currentBudget"
                    else -> "当前未设置 Token 上限"
                },
                icon = CodexGlyph.Tune,
                onClick = {
                    budgetInput = if (budgetEdited) budget else currentBudget
                    showBudgetEditor = true
                },
                modifier = Modifier.testTag("goal-advanced-toggle")
            )
            if (budgetEdited) {
                CodexTextAction(onClick = {
                    vm.editTaskPanelDraft(active) {
                        it.copy(goalBudget = "", goalBudgetEdited = false)
                    }
                }, modifier = Modifier.testTag("goal-budget-undo")) {
                    Text("撤销待保存的额度修改")
                }
                if (!safeBudget) {
                    AtlasFieldFeedback("Token 上限必须是正整数", isError = true)
                }
            }

        }
        PresentationPolicy.visibleErrors(errors, listOf(key, "goalWrite"))
            .forEach { ErrorText(it) }
    }
    if (active != null) {
        Surface(Modifier.fillMaxWidth(), color = Ink.elevated,
            border = BorderStroke(1.dp, Ink.border)) {
            CodexPrimaryButton(
                onClick = {
                    val threadId = requireNotNull(active) { "请先打开任务会话" }
                    val params = ThreadGoalRequestContract.set(
                        threadId = threadId, objective = objective, status = status,
                        budgetText = budget, editBudget = budgetEdited,
                        existingBudget = goal != null && !goal.isNull("tokenBudget")
                    )
                    vm.rpc("goalWrite", "thread/goal/set", params, after = {
                        vm.editTaskPanelDraft(threadId) {
                            it.copy(goalBudgetEdited = false)
                        }
                        vm.refresh("goal:$threadId", "thread/goal/get",
                            JSONObject().put("threadId", threadId))
                    })
                },
                enabled = objective.isNotBlank() &&
                    (!budgetEdited || safeBudget) && "goalWrite" !in loading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
                    .testTag("goal-sticky-save")
            ) { Text("保存目标") }
        }
    }
    }
    if (showBudgetEditor && active != null) {
        CodexEditDialog(
            title = "修改执行额度",
            description = "只在点击“应用额度”后将修改加入待保存目标。留空表示清除已有上限。",
            confirmLabel = "应用额度",
            onDismiss = { showBudgetEditor = false },
            onConfirm = {
                val existingLimit = goal?.opt("tokenBudget")
                    ?.takeIf { value -> value != JSONObject.NULL }?.toString().orEmpty()
                vm.editTaskPanelDraft(active) {
                    it.copy(
                        goalBudget = budgetInput,
                        goalBudgetEdited = budgetInput.isNotBlank() || existingLimit.isNotBlank()
                    )
                }
                showBudgetEditor = false
            },
            canConfirm = safeBudgetInput
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("" to "不限额度", "10000" to "1 万 Token", "50000" to "5 万 Token",
                    "100000" to "10 万 Token").forEach { (id, name) ->
                    AtlasChoiceRow(name, "", budgetInput == id, onClick = { budgetInput = id })
                }
            }
            ActionReplyRow(
                value = budgetInput,
                onValueChange = { budgetInput = it.filter(Char::isDigit) },
                label = { Text("其他 Token 上限") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("goal-budget-input")
            )
            if (!safeBudgetInput) {
                AtlasFieldFeedback("Token 上限必须是正整数", isError = true)
            }
            Text("保存目标时才会提交额度变更。取消编辑将保留当前额度。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmClear) CodexConfirmDialog(
        title = "清除当前任务目标",
        message = "当前会话的任务目标会被清除，历史会话内容继续保留。",
        confirmLabel = "确认清除",
        destructive = true,
        onConfirm = {
            vm.rpc("goalWrite", "thread/goal/clear",
                JSONObject().put("threadId", active).put("origin", "user"),
                after = {
                    vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active))
                    vm.editTaskPanelDraft(active) {
                        it.copy(goalText = "", goalStatus = "active",
                            goalBudget = "", goalBudgetEdited = false)
                    }
                })
            confirmClear = false
        },
        onDismiss = { confirmClear = false }
    )
}

@Composable
fun ThreadQueuePage(vm: CodexViewModel) {
    val active = vm.activeThread.collectAsState().value
    val rootKey = "queue:" + (active ?: "none")
    var pageCount by remember(active) { mutableIntStateOf(0) }
    val queueKeys = remember(rootKey, pageCount) { listOf(rootKey) + (1..pageCount).map { rootKey + ":page:" + it } }
    val data by vm.data.collectEntries(*queueKeys.toTypedArray())
    val errors by vm.errors.collectEntries(*(queueKeys + "queueWrite").toTypedArray())
    val loading by vm.loading.collectProjection { it.intersect((queueKeys + "queueWrite").toSet()) }
    val draft by vm.taskPanelDrafts.collectProjection { it[active]?.queueText.orEmpty() }
    var pendingDelete by remember(active) { mutableStateOf<JSONObject?>(null) }
    var pendingEdit by remember(active) { mutableStateOf<JSONObject?>(null) }
    var editText by rememberSaveable(active) { mutableStateOf("") }
    val result = data[rootKey]
    LaunchedEffect(result) { pageCount = 0 }
    val all = records(result) + (1..pageCount).flatMap {
        records(data[rootKey + ":page:" + it])
    }
    val currentKey = if (pageCount == 0) rootKey else rootKey + ":page:" + pageCount
    val nextCursor = data[currentKey]?.optString("nextCursor").orEmpty()
    val readKeys = listOf(rootKey) + (1..pageCount).map { rootKey + ":page:" + it }
    val hasAllPages = readKeys.all { it in data && it !in loading && it !in errors } && nextCursor.isBlank()
    val ids = all.map { it.optString("id") }
    val connection by vm.bridge.status.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val starting by vm.firstThreadStarting.collectAsState()
    val steering by vm.steerBusy.collectAsState()
    val permissionSaving by vm.securitySaveBusy.collectAsState()
    val transferBusy by AppDataTransfer.state.collectProjection { it.busy }
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val paused by vm.queuePausedThreads.collectProjection { active != null && active in it }
    val canStart = active != null && !running && !switching && !starting && !steering &&
        !permissionSaving && !transferBusy && !restarting &&
        connection == EngineStatus.Connected && "queueWrite" !in loading && rootKey !in loading && rootKey !in errors
    val canMutate = active != null && !switching && !starting && !steering &&
        !permissionSaving && !transferBusy && !restarting &&
        connection == EngineStatus.Connected && "queueWrite" !in loading
    val canReorder = canMutate && hasAllPages && QueueReorderPolicy.valid(ids)
    val rowCenters = remember(active, ids) { mutableStateMapOf<String, Float>() }
    var dragging by remember(active, ids) { mutableIntStateOf(-1) }
    var destination by remember(active, ids) { mutableIntStateOf(-1) }
    var dragY by remember(active, ids) { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current

    fun resetDrag() { dragging = -1; destination = -1; dragY = 0f }

    fun refreshQueue() {
        pageCount = 0
        vm.refresh(rootKey, "thread/queue/list",
            JSONObject().put("threadId", active).put("limit", 100))
    }

    fun reorder(from: Int, to: Int) {
        if (!canReorder || active == null) return
        val order = QueueReorderPolicy.move(ids, from, to) ?: return
        vm.rpc("queueWrite", "thread/queue/reorder",
            JSONObject().put("threadId", active)
                .put("queuedSubmissionIds", JSONArray(order)),
            after = { refreshQueue() })
    }

    LaunchedEffect(active) {
        if (active != null) refreshQueue()
    }

    ScreenColumn {
        SectionIntro("后续指令", "当前任务结束后按顺序执行的指令，可以修改、排序或删除。")
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("请先打开一个任务会话", style = MaterialTheme.typography.titleMedium)
                Text("队列属于具体会话；切换会话后将显示对应任务。", color = Ink.muted)
            }
            return@ScreenColumn
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("添加后续指令", style = MaterialTheme.typography.titleMedium)
            ActionReplyRow(draft, { text ->
                vm.editTaskPanelDraft(active) { it.copy(queueText = text) }
            }, modifier = Modifier.fillMaxWidth().testTag("thread-queue-draft"),
                minLines = 2, maxLines = 6,
                label = { Text("完成当前任务后做什么") },
                placeholder = { Text("描述明确任务及完成条件") }, directText = true)
            CodexSecondaryButton(onClick = {
                val originalDraft = draft
                val args = JSONObject().put("threadId", active)
                    .put("clientUserMessageId", UUID.randomUUID().toString())
                    .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", draft.trim())))
                vm.rpc("queueWrite", "thread/queue/add", args, after = {
                    vm.editTaskPanelDraft(active) {
                        it.copy(queueText =
                            ThreadDisplayPolicy.afterAcceptedDraft(it.queueText, originalDraft))
                    }
                    refreshQueue()
                })
            }, enabled = draft.isNotBlank() && canMutate,
                modifier = Modifier.fillMaxWidth()) {
                CodexIcon(CodexGlyph.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("排入后续")
            }
        }
        if (all.isNotEmpty() && !running) {
            CodexNotice(if (paused) "当前任务已停止，后续指令已保留。"
                else "当前会话空闲，可启动队首或指定后续指令。", NoticeTone.Info)
            CodexSecondaryButton(onClick = { active?.let { vm.startQueuedComposer(it) } },
                enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Text(if (paused) "恢复执行队列" else "开始执行队列")
            }
        }
        AtlasListHeading(title = "等待执行", total = all.size, action = {
            CodexIconAction(onClick = { refreshQueue() }) {
                CodexIcon(CodexGlyph.Retry, contentDescription = "刷新指令")
            }
        })
        if (rootKey in loading || (1..pageCount).any { rootKey + ":page:" + it in loading }) {
            AtlasOperationProgress("正在读取后续指令")
        }
        if (all.isEmpty() && data[rootKey] != null && rootKey !in loading && rootKey !in errors &&
            (1..pageCount).none { rootKey + ":page:" + it in loading || rootKey + ":page:" + it in errors }) {
            CodexEmptyState("暂无后续指令", "任务运行且输入新消息时，可以选择「排队发送」来添加。")
        }
        if (all.size > 1) {
            Text(if (canReorder) "长按顺序编号拖动排序，也可从更多操作中上移或下移。"
                else "完整队列读取并连接后可调整顺序。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
        all.forEachIndexed { index, item ->
            val input = item.optJSONArray("input") ?: JSONArray()
            val summary = remember(item) { queuedInputSummary(item) }
            val itemId = item.optString("id")
            val lifted = dragging == index
            val insertion = dragging >= 0 && destination == index && !lifted
            Box(Modifier.fillMaxWidth().zIndex(if (lifted) 1f else 0f)
                .onGloballyPositioned { coordinates ->
                    rowCenters[itemId] = coordinates.positionInRoot().y + coordinates.size.height / 2f
                }) {
                if (lifted) Box(Modifier.matchParentSize()
                    .background(Ink.recessed, RoundedCornerShape(16.dp))
                    .border(1.dp, Ink.controlBorder, RoundedCornerShape(16.dp)))
                MaterialPanel(Modifier.fillMaxWidth().graphicsLayer {
                    translationY = if (lifted) dragY - (rowCenters[itemId] ?: dragY) else 0f
                    shadowElevation = if (lifted) 8.dp.toPx() else 0f
                    shape = RoundedCornerShape(16.dp)
                }.then(if (insertion) Modifier.border(2.dp, Ink.jade, RoundedCornerShape(16.dp)) else Modifier)) {
                var showQueueActions by remember(itemId) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AtlasQueueDragHandle(index + 1, all.size, canReorder, itemId,
                        onClick = { showQueueActions = true },
                        onStart = {
                            val center = rowCenters[itemId]
                            if (center != null) {
                                dragging = index
                                destination = index
                                dragY = center
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onMove = { delta ->
                            if (dragging == index) {
                                dragY += delta
                                destination = QueueReorderPolicy.nearest(ids, rowCenters, dragY) ?: index
                            }
                        },
                        onEnd = {
                            val target = destination
                            val from = dragging
                            resetDrag()
                            if (from == index) reorder(index, target)
                        },
                        onCancel = { resetDrag() })
                    Spacer(Modifier.width(5.dp))
                    Text(when {
                        dragging == index && destination >= 0 -> "移至第 ${destination + 1} 项"
                        dragging >= 0 && destination == index -> "松手移动至此"
                        else -> "第 " + (index + 1) + " 项"
                    }, color = if (dragging >= 0 && (dragging == index || destination == index)) Ink.jade else Ink.muted,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Box {
                    CodexIconAction(
                        onClick = { showQueueActions = true },
                        enabled = "queueWrite" !in loading,
                        modifier = Modifier.testTag("queue-item-more-" + index)
                    ) {
                        CodexIcon(CodexGlyph.MoreVert, contentDescription = "管理第${index + 1}项指令")
                    }
                        if (showQueueActions) CodexFloatingActionMenu(
                    title = "第 ${index + 1} 项指令",
                    onDismiss = { showQueueActions = false },
                    actions = listOf(
                        CodexSheetAction(
                            label = "立即执行",
                            description = if (running) "当前任务结束后才能启动此项" else "启动这一项，其他指令保留在队列",
                            icon = CodexGlyph.PlayArrow,
                            enabled = canStart && itemId.isNotBlank(),
                            onClick = { active?.let { vm.startQueuedComposer(it, itemId) } }
                        ),
                        CodexSheetAction(
                            label = "移至队首",
                            description = "将此项作为下一条执行指令",
                            icon = CodexGlyph.ArrowUpward,
                            enabled = canReorder && index > 1,
                            onClick = { reorder(index, 0) }
                        ),
                        CodexSheetAction(
                            label = "上移",
                            description = "优先执行此项",
                            icon = CodexGlyph.ArrowUpward,
                            enabled = canReorder && index > 0 && "queueWrite" !in loading,
                            onClick = { reorder(index, index - 1) }
                        ),
                        CodexSheetAction(
                            label = "下移",
                            description = "延后执行此项",
                            icon = CodexGlyph.ArrowDownward,
                            enabled = canReorder && index < all.lastIndex && "queueWrite" !in loading,
                            onClick = { reorder(index, index + 1) }
                        ),
                        CodexSheetAction(
                            label = "编辑指令",
                            icon = CodexGlyph.Edit,
                            enabled = ThreadQueueUpdateContract.canEditAsText(input) && canMutate,
                            onClick = {
                                pendingEdit = item
                                editText = input.optJSONObject(0)?.optString("text").orEmpty()
                            }
                        ),
                        CodexSheetAction(
                            label = "删除指令",
                            description = "删除前会再次确认",
                            icon = CodexGlyph.DeleteOutline,
                            destructive = true,
                            enabled = canMutate,
                            onClick = { pendingDelete = item }
                        )
                    )
                )
                    }
                }
                Text(summary, style = MaterialTheme.typography.bodyLarge, maxLines = 8,
                    overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (nextCursor.isNotBlank()) {
            CodexSecondaryButton(onClick = {
                val page = pageCount + 1
                vm.refresh(rootKey + ":page:" + page, "thread/queue/list",
                    JSONObject().put("threadId", active)
                        .put("cursor", nextCursor).put("limit", 100))
                pageCount = page
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = currentKey !in loading && "queueWrite" !in loading) { Text("加载更多指令") }
        }
        // Compare user-visible errors, not raw RPC strings: different endpoints
        // can resolve to the same actionable message (e.g. an offline engine).
        PresentationPolicy.visibleErrors(errors,
            listOfNotNull(rootKey, currentKey.takeIf { it != rootKey }, "queueWrite"))
            .forEach { ErrorText(it) }
    }
    pendingEdit?.let { item ->
        CodexDialogFrame(
            onDismissRequest = { pendingEdit = null },
            title = { Text("编辑排队任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionReplyRow(
                        value = editText, onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("任务内容") }, minLines = 2, maxLines = 6, directText = true
                    )
                    errors["queueWrite"]?.let {
                        Text("保存失败：" + PresentationPolicy.readableError(it), color = Ink.red)
                    }
                }
            },
            confirmButton = {
                CodexSecondaryButton(
                    onClick = {
                        active?.let { id ->
                            vm.rpc("queueWrite", "thread/queue/update",
                                ThreadQueueUpdateContract.build(id, item.optString("id"), editText),
                                after = {
                                    pendingEdit = null
                                    refreshQueue()
                                })
                        }
                    },
                    enabled = active != null && item.optString("id").isNotBlank() &&
                        editText.isNotBlank() && canMutate
                ) { Text("保存修改") }
            },
            dismissButton = { CodexTextAction(onClick = { pendingEdit = null }) { Text("取消") } }
        )
    }

    pendingDelete?.let { item ->
        CodexConfirmDialog(
            title = "删除排队任务",
            message = "任务会从当前会话的待执行队列中移除。",
            detail = PresentationPolicy.meaningful(item.optString("summary"))
                .ifEmpty { "将删除当前选择的待执行任务" },
            confirmLabel = "确认删除",
            destructive = true,
            onConfirm = {
                vm.rpc("queueWrite", "thread/queue/delete",
                    JSONObject().put("threadId", active)
                        .put("queuedSubmissionId", item.optString("id")),
                    after = { refreshQueue() })
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Task-owned queue stays on top of the active work area instead of replacing it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadQueueQuickSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val active by vm.activeThread.collectAsState()
    val key = "queue:" + (active ?: "none")
    val data by vm.data.collectEntries(key)
    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        fullScreen = true,
        containerColor = Ink.canvas,
        contentColor = Ink.parchment
    ) {
        Column(Modifier.fillMaxWidth()
            .fillMaxHeight()
            .testTag("thread-queue-sheet")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CodexIconAction(
                    onClick = LocalCodexSheetClose.current ?: onDismiss,
                    modifier = Modifier.size(48.dp).testTag("thread-queue-close")
                ) {
                    CodexIcon(CodexGlyph.Close, contentDescription = "关闭后续指令")
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ThreadQueuePage(vm)
            }
        }
    }
}
