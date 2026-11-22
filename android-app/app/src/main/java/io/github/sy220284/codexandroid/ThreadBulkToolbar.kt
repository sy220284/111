package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Shared selection toolbar for the paginated conversation library. */
@Composable
internal fun ThreadBulkToolbar(
    selectedCount: Int,
    archived: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAction: (String) -> Unit
) {
    MaterialPanel(Modifier.fillMaxWidth().testTag("thread-bulk-toolbar")) {
        Text("已选 $selectedCount 条 · 仅针对当前筛选页",
            style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSelectAll,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .explainDisabled(!enabled, "任务运行或会话正在切换，暂不能全选"),
                enabled = enabled
            ) { Text("全选本页") }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .explainDisabled(!enabled || selectedCount == 0,
                        if (!enabled) "任务运行或会话正在切换" else "还未选中会话"),
                enabled = enabled && selectedCount > 0
            ) { Text("清空选择") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onAction(if (archived) "unarchive" else "archive") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("thread-bulk-archive")
                    .explainDisabled(!enabled || selectedCount == 0,
                        if (!enabled) "任务运行或会话正在切换" else "请先选择会话"),
                enabled = enabled && selectedCount > 0
            ) { Text(if (archived) "批量恢复" else "批量归档") }
            OutlinedButton(
                onClick = { onAction("delete") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("thread-bulk-delete")
                    .explainDisabled(!enabled || selectedCount == 0,
                        if (!enabled) "任务运行或会话正在切换" else "请先选择需要删除的会话"),
                enabled = enabled && selectedCount > 0
            ) { Text("批量删除", color = Ink.red) }
        }
    }
}
