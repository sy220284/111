package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Portable backup and restore are independent of the update-checking screen lifecycle. */
@Composable
internal fun LocalDataTransferCard(vm: CodexViewModel) {
    val context = LocalContext.current
    val state by AppDataTransfer.state.collectAsState()
    val work = observeGlobalWorkSnapshot(vm)
    val available = work.canDisruptCoreOrData
    val errors by vm.errors.collectAsState()
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val started = vm.bridge.runIfIdle({ vm.globalWorkSnapshot().canDisruptCoreOrData }) {
                vm.errors.value = vm.errors.value - "dataTransfer"
                AppDataTransfer.export(context, uri)
            }
            if (!started) vm.errors.value = vm.errors.value + ("dataTransfer" to
                vm.globalWorkSnapshot().blockReason.ifBlank { "运行状态已变化，请重试" })
        }
    }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingImport = uri }

    Surface(modifier = Modifier.fillMaxWidth().testTag("settings-backup"),
        shape = RoundedCornerShape(22.dp), color = Ink.canvas,
        border = BorderStroke(1.dp, Ink.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexIcon(CodexGlyph.SaveAlt, "本机备份", tint = Ink.copper)
                Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
            }
            Text("更换安装签名、重装应用前，先导出本机项目、会话和设置。账号凭据需在恢复后重新登录。开发运行组件和私有已安装依赖不随备份迁移，项目依赖按声明重新安装。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexSecondaryButton(
                    onClick = { exportPicker.launch("111-本机备份.zip") },
                    enabled = !state.busy && available,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("backup-export")
                ) { Text("导出数据") }
                CodexSecondaryButton(
                    onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !state.busy && available,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("backup-import")
                ) { Text("导入备份") }
            }
            if (!available) Text(work.blockReason,
                color = Ink.warning, style = MaterialTheme.typography.labelMedium)
            errors["dataTransfer"]?.let { ErrorText(it) }
            val message = state.result.ifBlank { LocalDataArchive.lastResult(context) }
            if (state.busy) AtlasOperationProgress(message.ifBlank { "正在处理本机备份" })
            else if (message.isNotBlank()) CodexNotice(message,
                if ("失败" in message || "未完成" in message) NoticeTone.Error
                else if (message.startsWith("已导出") || message.startsWith("已验证")) NoticeTone.Success
                else NoticeTone.Info)
            Text("备份保存在你选定的位置，可能包含私密文件；导入经完整验证后在下次启动时恢复。",
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        }
    }
    pendingImport?.let { uri ->
        CodexConfirmDialog(
            title = "确认导入本机备份",
            message = "将检查整个备份文件，下次完整启动时恢复其中的项目、会话与设置。" +
                "同名本机文件会被备份版本替换，请先确认当前任务已结束。" +
                if (available) "" else "\n\n" + work.blockReason,
            detail = "源文件：" + (uri.lastPathSegment ?: "所选备份"),
            confirmEnabled = available,
            confirmLabel = "核对并导入",
            destructive = true,
            onConfirm = {
                val started = vm.bridge.runIfIdle({ vm.globalWorkSnapshot().canDisruptCoreOrData }) {
                    vm.errors.value = vm.errors.value - "dataTransfer"
                    AppDataTransfer.import(context, uri)
                }
                if (started) pendingImport = null
                else vm.errors.value = vm.errors.value + ("dataTransfer" to
                    vm.globalWorkSnapshot().blockReason.ifBlank { "运行状态已变化，请重试" })
            },
            onDismiss = { pendingImport = null }
        )
    }
}
