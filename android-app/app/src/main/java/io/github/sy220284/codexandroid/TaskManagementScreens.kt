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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun ThreadGoalPage(vm: CodexViewModel) {
    val active by vm.activeThread.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val key = "goal:" + (active ?: "none")
    val goal = data[key]?.optJSONObject("goal")
    val taskDrafts by vm.taskPanelDrafts.collectAsState()
    val panelDraft = taskDrafts[active] ?: TaskPanelDraft()
    val objective = panelDraft.goalText
    val status = panelDraft.goalStatus
    val budget = panelDraft.goalBudget
    var confirmClear by remember(active) { mutableStateOf(false) }
    var showAdvancedBudget by remember(active) { mutableStateOf(false) }
    val safeBudget = budget.isBlank() || (budget.toLongOrNull()?.let { it > 0 } == true)

    LaunchedEffect(active) {
        if (active != null) vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active))
    }
    ScreenColumn {
        SectionIntro("TASK / OBJECTIVE", "任务目标", "设定希望达成的结果，并查看当前任务进度。")
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("请先从历史记录或任务对话打开一个会话", style = MaterialTheme.typography.titleMedium)
                Text("选中会话后即可读取、设定和更新该会话目标。", color = Ink.muted)
            }
            return@ScreenColumn
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Flag, null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Text("当前目标", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { vm.refresh(key, "thread/goal/get",
                    JSONObject().put("threadId", active)) }) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
            }
            if (key in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (goal == null) {
                Text("当前会话尚未设定目标", color = Ink.muted)
            } else {
                Text(goal.optString("objective"), style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatusPill(PresentationPolicy.goalStatus(goal.optString("status")),
                        goal.optString("status") == "active")
                }
                if (showAdvancedBudget) {
                    Text("已使用 Token：" + goal.optLong("tokensUsed") + " · 执行秒数：" +
                        goal.optLong("timeUsedSeconds"), color = Ink.muted)
                }
                OutlinedButton(onClick = {
                    vm.editTaskPanelDraft(active) {
                        it.copy(
                            goalText = goal.optString("objective"),
                            goalStatus = goal.optString("status").ifBlank { "active" },
                            goalBudget = goal.opt("tokenBudget")
                                ?.takeIf { value -> value != JSONObject.NULL }?.toString().orEmpty()
                        )
                    }
                }) { Text("编辑当前目标") }
                TextButton(onClick = { confirmClear = true }) { Text("清除目标") }
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text(if (goal == null) "设置新目标" else "更新目标", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(objective, { text ->
                vm.editTaskPanelDraft(active) { it.copy(goalText = text) }
            }, modifier = Modifier.fillMaxWidth(),
                label = { Text("目标说明") }, placeholder = { Text("写明期望结果与验收标准") },
                minLines = 3, maxLines = 7)
            Text("执行状态", style = MaterialTheme.typography.titleMedium)
            listOf("active" to "进行中", "paused" to "已暂停",
                "blocked" to "遇到阻碍", "complete" to "已完成").forEach { (value, title) ->
                Row(Modifier.fillMaxWidth().clickable {
                    vm.editTaskPanelDraft(active) { it.copy(goalStatus = value) }
                },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(status == value, onClick = {
                        vm.editTaskPanelDraft(active) { it.copy(goalStatus = value) }
                    })
                    Text(title)
                }
            }
            TextButton(onClick = {
                if (!showAdvancedBudget && budget.isBlank()) {
                    // Exposing the advanced control must preserve the server's existing limit.
                    val currentLimit = goal?.opt("tokenBudget")
                        ?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
                    if (currentLimit.isNotBlank()) vm.editTaskPanelDraft(active) {
                        it.copy(goalBudget = currentLimit)
                    }
                }
                showAdvancedBudget = !showAdvancedBudget
            }, modifier = Modifier.testTag("goal-advanced-toggle")) {
                Icon(if (showAdvancedBudget) Icons.Outlined.ExpandLess
                    else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(6.dp))
                Text(if (showAdvancedBudget) "收起高级设置" else "高级设置 · 执行额度")
            }
            if (showAdvancedBudget) {
                OutlinedTextField(budget, { text ->
                    vm.editTaskPanelDraft(active) {
                        it.copy(goalBudget = text.filter(Char::isDigit))
                    }
                },
                    label = { Text("Token 上限（可留空）" ) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (!safeBudget) Text("Token 上限必须是正整数", color = Ink.red)
            }
            OutlinedButton(
                onClick = {
                    val params = JSONObject().put("threadId", active)
                        .put("origin", "user")
                        .put("objective", objective.trim())
                        .put("status", status)
                    // A hidden advanced control leaves an existing budget unchanged.
                    // A visible empty control explicitly clears an existing limit.
                    if (showAdvancedBudget) {
                        if (budget.isNotBlank()) {
                            params.put("tokenBudget", budget.toLong())
                        } else if (goal != null && !goal.isNull("tokenBudget")) {
                            params.put("tokenBudget", JSONObject.NULL)
                        }
                    }
                    vm.rpc("goalWrite", "thread/goal/set", params, after = {
                        vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active))
                    })
                }, enabled = objective.isNotBlank() && safeBudget && "goalWrite" !in loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存目标") }
        }
        PresentationPolicy.visibleErrors(errors, listOf(key, "goalWrite"))
            .forEach { ErrorText(it) }
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
                        it.copy(goalText = "", goalStatus = "active", goalBudget = "")
                    }
                })
            confirmClear = false
        },
        onDismiss = { confirmClear = false }
    )
}

@Composable
fun ThreadQueuePage(vm: CodexViewModel) {
    val active by vm.activeThread.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val rootKey = "queue:" + (active ?: "none")
    var pageCount by remember(active) { mutableIntStateOf(0) }
    val taskDrafts by vm.taskPanelDrafts.collectAsState()
    val draft = taskDrafts[active]?.queueText.orEmpty()
    var pendingDelete by remember(active) { mutableStateOf<JSONObject?>(null) }
    var pendingEdit by remember(active) { mutableStateOf<JSONObject?>(null) }
    var editText by rememberSaveable(active) { mutableStateOf("") }
    val result = data[rootKey]
    val all = records(result) + (1..pageCount).flatMap {
        records(data[rootKey + ":page:" + it])
    }
    val currentKey = if (pageCount == 0) rootKey else rootKey + ":page:" + pageCount
    val nextCursor = data[currentKey]?.optString("nextCursor").orEmpty()
    val hasAllPages = result != null && currentKey in data && nextCursor.isBlank()
    val ids = all.map { it.optString("id") }
    val canReorder = hasAllPages && ids.size == ids.toSet().size && ids.none { it.isBlank() }

    fun refreshQueue() {
        pageCount = 0
        vm.refresh(rootKey, "thread/queue/list",
            JSONObject().put("threadId", active).put("limit", 100))
    }

    LaunchedEffect(active) {
        if (active != null) refreshQueue()
    }

    ScreenColumn {
        SectionIntro("TASK / QUEUE", "后续指令", "当前任务结束后按顺序执行的指令，可以修改、排序或删除。")
        StatusPill("试用功能", tone = StatusTone.Neutral)
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("请先打开一个任务会话", style = MaterialTheme.typography.titleMedium)
                Text("队列属于具体会话；切换会话后将显示对应任务。", color = Ink.muted)
            }
            return@ScreenColumn
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("添加后续指令", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft, { text ->
                vm.editTaskPanelDraft(active) { it.copy(queueText = text) }
            }, modifier = Modifier.fillMaxWidth().testTag("thread-queue-draft"),
                minLines = 2, maxLines = 6,
                label = { Text("完成当前任务后做什么") },
                placeholder = { Text("描述明确任务及完成条件") })
            OutlinedButton(onClick = {
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
            }, enabled = draft.isNotBlank() && "queueWrite" !in loading,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("排入后续")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("等待执行 · " + all.size, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { refreshQueue() }) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        if (rootKey in loading || (1..pageCount).any { rootKey + ":page:" + it in loading })
            LinearProgressIndicator(Modifier.fillMaxWidth())
        if (all.isEmpty() && data[rootKey] != null && rootKey !in loading) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("暂无后续指令", style = MaterialTheme.typography.titleMedium)
            Text("任务运行时，也可以在输入栏选择「完成后执行」来添加。", color = Ink.muted)
        }
        all.forEachIndexed { index, item ->
            val input = item.optJSONArray("input") ?: JSONArray()
            val summary = (0 until input.length()).mapNotNull { input.optJSONObject(it)?.optString("text") }
                .filter { it.isNotBlank() }.joinToString("\n").ifBlank { "非文本任务输入" }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 " + (index + 1) + " 项", color = Ink.copper,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val order = ids.toMutableList()
                        val swap = order[index - 1]
                        order[index - 1] = order[index]
                        order[index] = swap
                        vm.rpc("queueWrite", "thread/queue/reorder",
                            JSONObject().put("threadId", active)
                                .put("queuedSubmissionIds", JSONArray(order)), after = { refreshQueue() })
                    }, enabled = canReorder && index > 0 && "queueWrite" !in loading) {
                        Icon(Icons.Outlined.ArrowUpward, "上移")
                    }
                    IconButton(onClick = {
                        val order = ids.toMutableList()
                        val swap = order[index + 1]
                        order[index + 1] = order[index]
                        order[index] = swap
                        vm.rpc("queueWrite", "thread/queue/reorder",
                            JSONObject().put("threadId", active)
                                .put("queuedSubmissionIds", JSONArray(order)), after = { refreshQueue() })
                    }, enabled = canReorder && index < all.lastIndex && "queueWrite" !in loading) {
                        Icon(Icons.Outlined.ArrowDownward, "下移")
                    }
                    IconButton(
                        onClick = {
                            pendingEdit = item
                            editText = input.optJSONObject(0)?.optString("text").orEmpty()
                        },
                        enabled = ThreadQueueUpdateContract.canEditAsText(input) &&
                            "queueWrite" !in loading
                    ) {
                        Icon(Icons.Outlined.Edit, "编辑文本任务")
                    }
                    IconButton(onClick = { pendingDelete = item },
                        enabled = "queueWrite" !in loading) {
                        Icon(Icons.Outlined.DeleteOutline, "删除")
                    }
                }
                Text(summary, style = MaterialTheme.typography.bodyLarge, maxLines = 8,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (nextCursor.isNotBlank()) {
            OutlinedButton(onClick = {
                val page = pageCount + 1
                vm.refresh(rootKey + ":page:" + page, "thread/queue/list",
                    JSONObject().put("threadId", active)
                        .put("cursor", nextCursor).put("limit", 100))
                pageCount = page
            }, modifier = Modifier.fillMaxWidth()) { Text("加载更多指令") }
        }
        // Compare user-visible errors, not raw RPC strings: different endpoints
        // can resolve to the same actionable message (e.g. an offline engine).
        PresentationPolicy.visibleErrors(errors,
            listOfNotNull(rootKey, currentKey.takeIf { it != rootKey }, "queueWrite"))
            .forEach { ErrorText(it) }
    }
    pendingEdit?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = { Text("编辑排队任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editText, onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("任务内容") }, minLines = 2, maxLines = 6
                    )
                    errors["queueWrite"]?.let {
                        Text("保存失败：" + PresentationPolicy.readableError(it), color = Ink.red)
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
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
                        editText.isNotBlank() && "queueWrite" !in loading
                ) { Text("保存修改") }
            },
            dismissButton = { TextButton(onClick = { pendingEdit = null }) { Text("取消") } }
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.canvas,
        contentColor = Ink.parchment
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)
            .testTag("thread-queue-sheet")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("thread-queue-close")
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭后续指令")
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ThreadQueuePage(vm)
            }
        }
    }
}
