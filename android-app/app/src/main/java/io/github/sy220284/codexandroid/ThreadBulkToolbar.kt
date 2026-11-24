package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val extras: @Composable () -> Unit = {
        var open by remember { mutableStateOf(false) }
        Box {
            CodexIconAction(onClick = { open = true }, enabled = enabled && selectedCount > 0,
                modifier = Modifier.size(48.dp).testTag("thread-bulk-organize")) {
                CodexIcon(CodexGlyph.MoreHoriz, "整理所选会话")
            }
            CodexPopupMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf("move" to "移动到分组", "pin" to "批量置顶",
                    "unpin" to "批量取消置顶", "clear" to "清空选择").forEach { (action, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        open = false
                        if (action == "clear") onClear() else onAction(action)
                    })
                }
            }
        }
    }
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
                Text("已选 $selectedCount 条 · 仅针对当前筛选结果",
                    color = Ink.parchment, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    CodexIconAction(onClick = onSelectAll, enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("thread-bulk-select-all")) {
                        CodexIcon(CodexGlyph.SelectAll, contentDescription = "全选当前结果")
                    }
                    extras()
                    CodexIconAction(onClick = onFinish,
                        modifier = Modifier.size(48.dp).testTag("thread-bulk-finish")) {
                        CodexIcon(CodexGlyph.Check, contentDescription = "完成选择")
                    }
                }
                CodexSecondaryButton(
                    onClick = { onAction(if (archived) "unarchive" else "archive") },
                    enabled = allowAction,
                    modifier = Modifier.fillMaxWidth().testTag("thread-bulk-archive")
                        .explainDisabled(!allowAction,
                            if (!enabled) "批量操作暂不可用" else "请先选择会话")
                ) {
                    CodexIcon(if (archived) CodexGlyph.Unarchive else CodexGlyph.Archive, null)
                    Spacer(Modifier.width(8.dp))
                    Text(archiveLabel)
                }
                CodexTextAction(
                    onClick = { onAction("delete") }, enabled = allowAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("thread-bulk-delete")
                        .explainDisabled(!allowAction, if (!enabled)
                            "批量操作暂不可用" else "请先选择需要删除的会话")
                ) {
                    CodexIcon(CodexGlyph.DeleteOutline, null, tint = Ink.red)
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
                CodexIconAction(onClick = onSelectAll, enabled = enabled,
                    modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.SelectAll, contentDescription = "全选当前结果")
                }
                extras()
                CodexIconAction(onClick = onFinish, modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.Check, contentDescription = "完成选择")
                }
                CodexIconAction(
                    onClick = { onAction(if (archived) "unarchive" else "archive") },
                    enabled = allowAction,
                    modifier = Modifier.size(48.dp).testTag("thread-bulk-archive")
                        .explainDisabled(!allowAction,
                            if (!enabled) "批量操作暂不可用" else "请先选择会话")
                ) {
                    CodexIcon(if (archived) CodexGlyph.Unarchive else CodexGlyph.Archive,
                        contentDescription = archiveLabel)
                }
                CodexIconAction(
                    onClick = { onAction("delete") }, enabled = allowAction,
                    modifier = Modifier.size(48.dp).testTag("thread-bulk-delete")
                        .explainDisabled(!allowAction, if (!enabled)
                            "批量操作暂不可用" else "请先选择需要删除的会话")
                ) {
                    CodexIcon(CodexGlyph.DeleteOutline, contentDescription = "批量永久删除",
                        tint = if (allowAction) Ink.red else Ink.muted)
                }
            }
        }
    }
}
