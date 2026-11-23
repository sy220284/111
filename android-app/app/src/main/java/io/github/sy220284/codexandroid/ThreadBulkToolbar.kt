package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 单选栏贴底，大字/屏幕朗读布局显式堆叠高风险动作。 */
@Composable
internal fun ThreadBulkToolbar(
    selectedCount: Int,
    archived: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAction: (String) -> Unit,
    onFinish: () -> Unit = onClear
) {
    val allowAction = enabled && selectedCount > 0
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val archiveLabel = if (archived) "批量恢复" else "批量归档"
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("thread-bulk-toolbar"),
        color = Ink.elevated,
        border = BorderStroke(1.dp, Ink.border),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    ) {
        if (largeText) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("已选 $selectedCount 条 · 仅针对当前筛选页",
                    color = Ink.parchment, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSelectAll, enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("thread-bulk-select-all")) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = "全选本页")
                    }
                    IconButton(onClick = onFinish,
                        modifier = Modifier.size(48.dp).testTag("thread-bulk-finish")) {
                        Icon(Icons.Outlined.Check, contentDescription = "完成选择")
                    }
                }
                CodexSecondaryButton(
                    onClick = { onAction(if (archived) "unarchive" else "archive") },
                    enabled = allowAction,
                    modifier = Modifier.fillMaxWidth().testTag("thread-bulk-archive")
                        .explainDisabled(!allowAction,
                            if (!enabled) "批量操作暂不可用" else "请先选择会话")
                ) {
                    Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null)
                    Spacer(Modifier.width(8.dp))
                    Text(archiveLabel)
                }
                TextButton(
                    onClick = { onAction("delete") }, enabled = allowAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("thread-bulk-delete")
                        .explainDisabled(!allowAction, if (!enabled)
                            "批量操作暂不可用" else "请先选择需要删除的会话")
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = Ink.red)
                    Spacer(Modifier.width(8.dp))
                    Text("永久删除", color = Ink.red)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("已选 $selectedCount 条", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = Ink.parchment)
                IconButton(onClick = onSelectAll, enabled = enabled,
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.SelectAll, contentDescription = "全选本页")
                }
                IconButton(onClick = onFinish, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Check, contentDescription = "完成选择")
                }
                IconButton(
                    onClick = { onAction(if (archived) "unarchive" else "archive") },
                    enabled = allowAction,
                    modifier = Modifier.size(48.dp).testTag("thread-bulk-archive")
                        .explainDisabled(!allowAction,
                            if (!enabled) "批量操作暂不可用" else "请先选择会话")
                ) {
                    Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = archiveLabel)
                }
                IconButton(
                    onClick = { onAction("delete") }, enabled = allowAction,
                    modifier = Modifier.size(48.dp).testTag("thread-bulk-delete")
                        .explainDisabled(!allowAction, if (!enabled)
                            "批量操作暂不可用" else "请先选择需要删除的会话")
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "批量永久删除",
                        tint = if (allowAction) Ink.red else Ink.muted)
                }
            }
        }
    }
}
