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
    var objective by rememberSaveable(active) { mutableStateOf("") }
    var status by rememberSaveable(active) { mutableStateOf("active") }
    var budget by rememberSaveable(active) { mutableStateOf("") }
    var confirmClear by remember(active) { mutableStateOf(false) }
    val safeBudget = budget.isBlank() || (budget.toLongOrNull()?.let { it > 0 } == true)

    LaunchedEffect(active) {
        if (active != null) vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active))
    }
    ScreenColumn {
        SectionIntro("TASK / OBJECTIVE", "任务目标", "管理当前会话的目标、执行状态与可选额度。")
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
                Text("正在执行的目标", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
                    StatusPill(goal.optString("status"), goal.optString("status") == "active")
                    goal.opt("tokenBudget")?.takeIf { it != JSONObject.NULL }?.let {
                        Text("额度：" + it, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("已使用 Token：" + goal.optLong("tokensUsed") + " · 执行秒数：" +
                    goal.optLong("timeUsedSeconds"), color = Ink.muted)
                OutlinedButton(onClick = {
                    objective = goal.optString("objective")
                    status = goal.optString("status").ifBlank { "active" }
                    budget = goal.opt("tokenBudget")?.takeIf { it != JSONObject.NULL }?.toString() ?: ""
                }) { Text("编辑当前目标") }
                TextButton(onClick = { confirmClear = true }) { Text("清除目标") }
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text(if (goal == null) "设置新目标" else "更新目标", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(objective, { objective = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("目标说明") }, placeholder = { Text("写明期望结果与验收标准") },
                minLines = 3, maxLines = 7)
            Text("执行状态", style = MaterialTheme.typography.titleMedium)
            listOf("active" to "进行中", "paused" to "已暂停",
                "blocked" to "遇到阻碍", "complete" to "已完成").forEach { (value, title) ->
                Row(Modifier.fillMaxWidth().clickable { status = value },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(status == value, onClick = { status = value })
                    Text(title)
                }
            }
            OutlinedTextField(budget, { budget = it.filter(Char::isDigit) },
                label = { Text("Token 上限（可留空）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            if (!safeBudget) Text("Token 上限必须是正整数", color = Ink.red)
            Button(
                onClick = {
                    val params = JSONObject().put("threadId", active)
                        .put("origin", "user")
                        .put("objective", objective.trim())
                        .put("status", status)
                    if (budget.isNotBlank()) {
                        params.put("tokenBudget", budget.toLong())
                    } else if (goal != null && !goal.isNull("tokenBudget")) {
                        // The protocol uses Option<Option<i64>>: explicit null clears a prior limit.
                        params.put("tokenBudget", JSONObject.NULL)
                    }
                    vm.rpc("goalWrite", "thread/goal/set", params, after = {
                        vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active))
                    })
                }, enabled = objective.isNotBlank() && safeBudget && "goalWrite" !in loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存目标") }
        }
        errors[key]?.let { ErrorText(it) }
        errors["goalWrite"]?.let { ErrorText(it) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清除当前任务目标") },
        text = { Text("这将清除当前会话目标记录，已产生的会话内容仍然保留。") },
        confirmButton = { TextButton(onClick = {
            vm.rpc("goalWrite", "thread/goal/clear",
                JSONObject().put("threadId", active).put("origin", "user"),
                after = { vm.refresh(key, "thread/goal/get", JSONObject().put("threadId", active)) })
            confirmClear = false
            objective = ""; budget = ""; status = "active"
        }) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
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
    var draft by rememberSaveable(active) { mutableStateOf("") }
    var pendingDelete by remember(active) { mutableStateOf<JSONObject?>(null) }
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
        SectionIntro("TASK / QUEUE", "任务队列", "任务可以逐条排队执行，支持新增、删除、调整顺序与分页浏览。")
        StatusPill("上游实验接口", false)
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("请先打开一个任务会话", style = MaterialTheme.typography.titleMedium)
                Text("队列属于具体会话；切换会话后将显示对应任务。", color = Ink.muted)
            }
            return@ScreenColumn
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("添加待执行任务", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 6,
                label = { Text("下一步要做什么") },
                placeholder = { Text("描述明确任务及完成条件") })
            Button(onClick = {
                val args = JSONObject().put("threadId", active)
                    .put("clientUserMessageId", UUID.randomUUID().toString())
                    .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", draft.trim())))
                vm.rpc("queueWrite", "thread/queue/add", args, after = { refreshQueue() })
                draft = ""
            }, enabled = draft.isNotBlank() && "queueWrite" !in loading,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("加入队列")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("待执行项目 · " + all.size, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { refreshQueue() }) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        if (rootKey in loading || (1..pageCount).any { rootKey + ":page:" + it in loading })
            LinearProgressIndicator(Modifier.fillMaxWidth())
        if (all.isEmpty() && rootKey !in loading) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("当前队列为空", style = MaterialTheme.typography.titleMedium)
            Text("添加任务后可在此调整执行先后顺序。", color = Ink.muted)
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
                    IconButton(onClick = { pendingDelete = item }) {
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
            }, modifier = Modifier.fillMaxWidth()) { Text("加载更多任务") }
        }
        errors[rootKey]?.let { ErrorText(it) }
        errors[currentKey]?.let { ErrorText(it) }
        errors["queueWrite"]?.let { ErrorText(it) }
    }
    pendingDelete?.let { item ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("删除排队任务") },
            text = { Text("任务会从当前会话的待执行队列中移除。") },
            confirmButton = { TextButton(onClick = {
                vm.rpc("queueWrite", "thread/queue/delete",
                    JSONObject().put("threadId", active)
                        .put("queuedSubmissionId", item.optString("id")),
                    after = { refreshQueue() })
                pendingDelete = null
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
