package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The audit log is private to this device and can be cleared by its owner. */
@Composable
internal fun ApprovalAuditSection(
    records: List<ApprovalAuditRecord>,
    error: String?,
    onClear: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    MaterialPanel(Modifier.fillMaxWidth().testTag("approval-audit")) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("本机审批记录", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            CodexTextAction(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).testTag("approval-audit-toggle")
            ) {
                Text(if (expanded) "收起" else "查看 ${records.size} 条")
                CodexIcon(if (expanded) CodexGlyph.ExpandLess
                    else CodexGlyph.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) {
            Text("仅保存在这台设备，最多保留最近 100 条。命令可能包含私密信息，可随时清除。",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            if (records.isEmpty()) {
                Text("尚无已提交的审批记录", color = Ink.muted)
            } else {
                records.take(20).forEach { record ->
                    DividerLine()
                    val time = formatter.format(
                        Instant.ofEpochMilli(record.timeMs).atZone(ZoneId.systemDefault()))
                    Text("$time · ${record.operation} · ${record.decision}",
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    Text(record.summary, style = MaterialTheme.typography.bodyMedium)
                }
                if (records.size > 20) Text("仅显示最近 20 条，其他记录仍在本机保留。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                CodexSecondaryButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("approval-audit-clear")
                ) { Text("清除所有审批记录", color = Ink.red) }
            }
        }
        error?.let { ErrorText(it) }
    }
    if (confirmClear) CodexConfirmDialog(
        title = "清除本机审批记录",
        message = "将清除本设备保存的审批决定记录。当前尚未处理的审批请求保持不变。",
        detail = "本机共 ${records.size} 条审批记录。",
        destructive = true, confirmLabel = "确认清除",
        onConfirm = { onClear(); confirmClear = false },
        onDismiss = { confirmClear = false }
    )
}
