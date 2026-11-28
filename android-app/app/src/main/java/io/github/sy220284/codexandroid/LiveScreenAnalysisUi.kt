package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun LiveScreenAnalysisPanel(vm: CodexViewModel, owner: String, model: String?,
                                     question: String, share: ScreenShareSession.State, revision: Long,
                                     enabled: Boolean) {
    val controller = vm.composerContent.liveScreenAnalysis
    val active by controller.state.collectAsStateWithLifecycle()
    val all by controller.resultsByOwner.collectAsStateWithLifecycle()
    val epoch by vm.bridge.connectedSession.collectAsStateWithLifecycle()
    val selection by vm.modelId.collectAsStateWithLifecycle()
    val live = all[owner]?.takeIf { it.revision == revision && it.epoch == epoch && it.selection == selection &&
        model != null && (model.isBlank() || it.model == model) } ?: LiveScreenAnalysisController.State()
    var confirm by remember(owner, revision, epoch, model, selection) { mutableStateOf(false) }
    var review by remember(owner, revision, epoch, model, selection) { mutableStateOf<ScreenAnalysisResults?>(null) }
    val mine = live.owner == owner && live.revision == revision
    if (model != null && share.owner == owner && share.capturing && share.revision == revision && !active.running && live.observations.isEmpty()) {
        CodexTextAction(onClick = { confirm = true }, enabled = enabled) { Text("开启持续分析") }
    }
    if (mine && (live.running || live.observations.isNotEmpty() || live.message.isNotBlank())) {
        WorkSection(Modifier.fillMaxWidth(), WorkSectionKind.Status) {
        if (live.running) AtlasOperationProgress("请求预算 · 已使用 ${live.requests}/12 次",
            progress = (live.requests / 12f).coerceIn(0f, 1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (live.running) "持续分析中" else live.message,
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (live.observations.isNotEmpty()) CodexTextAction(onClick = { review = live.results }) {
                Text(if (live.pendingObservations.isEmpty()) "查看" else "查看 · ${live.pendingObservations.size}条新增")
            }
            if (live.running) CodexTextAction(onClick = { controller.stop() }) { Text("停止分析") }
            else CodexTextAction(onClick = { controller.dismiss(owner) }) { Text("清除") }
        }
        }
    } else if (active.running) {
        CodexTextAction(onClick = { controller.stop() }) { Text("停止其他会话的持续分析") }
    }
    if (confirm && model != null) CodexConfirmDialog(
        title = "开启持续屏幕分析",
        message = "持续共享期间，当前会话所选模型将接收变化的屏幕采样画面、当前问题及会话上下文，可能产生费用。请避免显示敏感内容。",
        detail = "最多5分钟、12次请求；每次最多3幅画面，串行分析，至少间隔10秒。停止共享或停止分析立即结束，不自动执行任何任务。",
        confirmLabel = "同意并开始",
        onConfirm = { confirm = false; controller.start(owner, model, question) },
        onDismiss = { confirm = false })
    review?.takeIf { mine }?.let { snapshot ->
        val pending = (snapshot.observations.size - live.results.appliedCount).coerceAtLeast(0)
        CodexConfirmDialog(
            title = "屏幕分析结果",
            message = "已保留${snapshot.observations.size}条完整结果。此次加入${pending}条新增内容；继续分析产生的结果可稍后查看。模型描述可能存在遗漏。",
            detail = snapshot.observations.mapIndexed { index, observation ->
                "${if (index < live.results.appliedCount) "已加入" else "待加入"} · [${observation.startMs}–${observation.endMs}ms]\n${observation.text}"
            }.joinToString("\n\n"),
            confirmLabel = if (pending == 0) "已加入草稿" else "加入${pending}条新增结果",
            confirmEnabled = pending > 0 && vm.globalWorkSnapshot().canStartNewTask,
            onConfirm = { controller.apply(owner, snapshot.observations.size); review = null },
            onDismiss = { review = null })
    }
}

