package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

/** 代码审查的唯一业务表单；页面深链和工作区弹层共用真实 review/start。 */
@Composable
fun ReviewScopePanel(
    vm: CodexViewModel,
    onStarted: () -> Unit,
    onSelectThread: () -> Unit
) {
    val active by vm.activeThread.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val reviewDraft = vm.taskPanelDraft(active)
    val selection = reviewDraft.reviewScope
    val value = reviewDraft.reviewText
    var confirm by remember { mutableStateOf(false) }
    var submitted by remember(active) { mutableStateOf(false) }
    val requestKey = "review:$active"
    LaunchedEffect(submitted, data[requestKey]) {
        if (submitted && data[requestKey] != null) onStarted()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (active == null) {
            RequiresThreadPanel("代码审查", onSelectThread)
        } else {
            Text("审查范围", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("uncommittedChanges", "尚未提交的改动", "检查当前变更"),
                    Triple("baseBranch", "相对基线分支", "比较分支与当前修改"),
                    Triple("commit", "指定提交", "检查指定 Commit"),
                    Triple("custom", "自定义范围", "输入具体审查要求")
                ).forEach { (id, label, description) ->
                    AtlasChoiceRow(label, description, selection == id,
                        onClick = { vm.editTaskPanelDraft(active) { it.copy(reviewScope = id) } },
                        enabled = !running && requestKey !in loading)
                }
            }
            if (selection != "uncommittedChanges") {
                ActionReplyRow(
                    value = value, onValueChange = { text ->
                        vm.editTaskPanelDraft(active) { it.copy(reviewText = text) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(when(selection) {
                        "baseBranch" -> "基线分支"
                        "commit" -> "Commit SHA"
                        else -> "审查说明"
                    }) }, minLines = 1, maxLines = 4
                )
            }
            if (running) CodexNotice("当前任务执行中，完成后再发起审查。", NoticeTone.Info)
            CodexPrimaryButton(
                onClick = { confirm = true },
                enabled = !running && requestKey !in loading &&
                    (selection == "uncommittedChanges" || value.isNotBlank()),
                modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)
            ) {
                CodexIcon(CodexGlyph.FactCheck, null)
                Spacer(Modifier.width(8.dp))
                Text("发起代码审查")
            }
        }
        if (submitted && requestKey in loading) AtlasOperationProgress("正在提交代码审查")
        if (submitted) errors[requestKey]?.let { ErrorText(it) }
        if (submitted) data[requestKey]?.let { response ->
            val status = response.optJSONObject("turn")?.optString("status").orEmpty()
            CodexNotice("内核已受理代码审查。审查过程和结果将在会话任务流中更新。",
                NoticeTone.Success, actionLabel = "返回任务", onAction = onStarted)
            val readableStatus = PresentationPolicy.timelineStatus(status)
            if (readableStatus.isNotEmpty()) StatusPill(readableStatus,
                good = readableStatus != "失败")
        }
    }
    if (confirm && active != null) {
        CodexConfirmDialog(
            title = "发起代码审查",
            message = "内核将在当前会话中创建审查任务；结果返回任务流。",
            detail = "审查范围：" + when (selection) {
                "uncommittedChanges" -> "尚未提交的改动"
                "baseBranch" -> "相对分支：" + value
                "commit" -> "指定提交：" + value
                else -> "自定义要求：" + value
            },
            confirmLabel = "开始审查",
            onConfirm = {
                val target = JSONObject().put("type", selection)
                when(selection) {
                    "baseBranch" -> target.put("branch", value.trim())
                    "commit" -> target.put("sha", value.trim())
                    "custom" -> target.put("instructions", value.trim())
                }
                submitted = true
                active?.let { vm.conversationActions.review(it, target) }
                confirm = false
            },
            onDismiss = { confirm = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScopeSheet(
    vm: CodexViewModel, onDismiss: () -> Unit, navigate: (String) -> Unit
) {
    CodexDetailSheet(title = "发起代码审查", onDismiss = onDismiss,
        startExpanded = true, closeDescription = "关闭代码审查",
        subtitle = "选择审查范围；任务由本机 Codex 处理。") {
        ReviewScopePanel(vm,
            onStarted = { onDismiss(); navigate("chat") },
            onSelectThread = { onDismiss(); navigate("threads") })
        Spacer(Modifier.height(15.dp))
    }
}
