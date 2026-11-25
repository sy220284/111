package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MediaAnalysisActions(paths: List<String>, state: MediaAnalysisController.State,
                                  owner: String, enabled: Boolean,
                                  onChoose: (String, QwenMediaWire.Mode) -> Unit, onCancel: () -> Unit,
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
        if (state.result != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("媒体分析结果已就绪", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                CodexTextAction(onClick = onReview) { Text("查看") }
                CodexTextAction(onClick = onDismiss) { Text("清除") }
            }
            return
        }
    }
    paths.forEachIndexed { index, path ->
        val kind = LocalMediaAttachmentPolicy.kind(path)
        if (kind != "image") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                Text("附件 ${index + 1}", style = MaterialTheme.typography.labelSmall, modifier = Modifier)
                QwenMediaWire.Mode.entries.filter { QwenMediaWire.accepts(it, kind, File(path).length(), LocalMediaAttachmentPolicy.hasAudio(path)) }.forEach { mode ->
                    CodexTextAction(onClick = { onChoose(path, mode) }, enabled = enabled && !state.busy) { Text(mode.label) }
                }
            }
        }
    }
}

@Composable
internal fun MediaAnalysisReview(state: MediaAnalysisController.State, onDismiss: () -> Unit, onApply: (Boolean) -> Unit) {
    // Shared confirmation surface; keep the original media attached by default.
    CodexConfirmDialog(title = state.mode?.label.orEmpty(), message = "模型分析资料可能有误，核对后再加入草稿。",
        detail = state.result.orEmpty(), confirmLabel = "保留附件并加入草稿", onConfirm = { onApply(false) },
        onDismiss = onDismiss)
}
