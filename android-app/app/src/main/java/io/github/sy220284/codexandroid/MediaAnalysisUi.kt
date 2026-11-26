package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MediaAnalysisActions(paths: List<String>, state: MediaAnalysisController.State,
                                  owner: String, enabled: Boolean,
                                  onChoose: (String, MediaAnalysisMode) -> Unit, onContinue: (String, MediaAnalysisMode) -> Unit,
                                  onSelectRecord: (String) -> Unit, onCancel: () -> Unit,
                                  onReview: () -> Unit, onDismiss: () -> Unit) {
    if (state.owner == owner) {
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AtlasOperationProgress("${state.mode?.label.orEmpty()} ${state.completed}/${state.total}",
                        progress = if (state.total > 0) state.completed.toFloat() / state.total else null)
                }
                CodexTextAction(onClick = onCancel) { Text("取消") }
            }
            return
        }
        state.error?.let { ErrorText(it) }
        if (state.result == null && (state.checkpoint != null || state.scope != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保留的分析记录", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (state.checkpoint != null && state.source != null && state.mode != null)
                    CodexTextAction(onClick = { onContinue(state.source, state.mode) }, enabled = enabled) { Text("继续剩余") }
                CodexTextAction(onClick = onDismiss) { Text("清除记录") }
            }
        }
        if (state.result != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("媒体分析结果已就绪", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (state.checkpoint?.complete == false && state.source != null && state.mode != null)
                    CodexTextAction(onClick = { onContinue(state.source, state.mode) }, enabled = enabled) { Text("继续剩余") }
                CodexTextAction(onClick = onReview) { Text("查看") }
                CodexTextAction(onClick = onDismiss) { Text("清除") }
            }
        }
        var historyOpen by remember(owner) { mutableStateOf(false) }
        if (state.history.size > 1) {
            CodexTextAction(onClick = { historyOpen = !historyOpen }) { Text("分析记录 · " + state.history.size) }
            if (historyOpen) state.history.forEach { record ->
                CodexTextAction(onClick = { onSelectRecord(record.identity) }, enabled = enabled && record.identity != state.checkpoint?.identity) { Text(record.label) }
            }
        }
    }
    var advanced by remember(owner) { mutableStateOf(false) }
    if (paths.isNotEmpty()) CodexTextAction(onClick = { advanced = !advanced }) { Text(if (advanced) "收起媒体处理选项" else "媒体处理选项") }
    if (!advanced) return
    paths.forEachIndexed { index, path ->
        val kind = LocalMediaAttachmentPolicy.kind(path)
        if (kind in setOf("image", "audio", "video")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                Text("附件 ${index + 1}", style = MaterialTheme.typography.labelSmall, modifier = Modifier)
                val metadata by loadCodexUiState<Result<Pair<Long, Boolean>>?>(null, path) {
                    codexLoadResult { File(path).length() to LocalMediaAttachmentPolicy.hasAudio(path) }
                }
                if (metadata == null) Text("正在读取媒体资料")
                if (metadata?.isFailure == true) ErrorText("媒体资料无法读取，请移除并重新添加")
                val details = metadata?.getOrNull()
                (details?.let { MediaAnalysisPlan.modes(kind, it.second) } ?: emptyList()).forEach { mode ->
                    CodexTextAction(onClick = { onChoose(path, mode) }, enabled = enabled && !state.busy) { Text(mode.label) }
                }
            }
        }
    }
}

@Composable
internal fun MediaAnalysisReview(state: MediaAnalysisController.State, onDismiss: () -> Unit, onApply: (Boolean) -> Unit) {
    var textOnly by androidx.compose.runtime.remember(state.source) { androidx.compose.runtime.mutableStateOf(false) }
    if (textOnly) CodexConfirmDialog(title = "仅使用分析文字",
        message = if (state.checkpoint?.complete == false)
            "仅加入已完成的分析文字，保留附件供继续分析剩余部分。全部完成后可移除草稿附件。"
            else "从本次草稿移除该媒体附件，原始文件与派生资料继续保留。适合仅使用文字的模型。",
        detail = state.result.orEmpty(), confirmLabel = "保留原文件并加入文字",
        onConfirm = { onApply(true) }, onDismiss = { textOnly = false })
    else CodexDialogFrame(onDismissRequest = onDismiss,
        title = { Text(state.mode?.label.orEmpty()) },
        text = {
            Column(Modifier.heightIn(max = atlasSheetHeight() * .56f)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型：" + state.model + "。核对分析资料后选择发送形式。")
                SelectionContainer { Text(state.result.orEmpty()) }
            }
        },
        confirmButton = { CodexPrimaryButton(enabled = state.checkpoint?.let { it.applied < it.parts.size } != false,
            onClick = { onApply(false) }) { Text("保留附件") } },
        dismissButton = { CodexTextAction(enabled = state.checkpoint?.let { it.applied < it.parts.size } != false, onClick = { textOnly = true }) { Text("仅使用文字") } })
}
