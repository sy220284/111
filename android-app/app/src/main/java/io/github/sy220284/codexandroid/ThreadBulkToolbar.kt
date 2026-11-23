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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 贴底的轻量批量工具栏，不再把列表推到第二屏。 */
@Composable
internal fun ThreadBulkToolbar(
    selectedCount: Int,
    archived: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAction: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("thread-bulk-toolbar"),
        color = Ink.elevated,
        border = BorderStroke(1.dp, Ink.border),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("已选 $selectedCount 条", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = Ink.parchment)
            IconButton(onClick = onSelectAll, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "全选本页")
            }
            IconButton(onClick = onClear, enabled = enabled && selectedCount > 0,
                modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Clear, contentDescription = "清空选择")
            }
            IconButton(
                onClick = { onAction(if (archived) "unarchive" else "archive") },
                enabled = enabled && selectedCount > 0,
                modifier = Modifier.size(48.dp).testTag("thread-bulk-archive")
            ) {
                Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (archived) "批量恢复" else "批量归档")
            }
            IconButton(
                onClick = { onAction("delete") },
                enabled = enabled && selectedCount > 0,
                modifier = Modifier.size(48.dp).testTag("thread-bulk-delete")
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "批量永久删除",
                    tint = if (enabled && selectedCount > 0) Ink.red else Ink.muted)
            }
        }
    }
}
