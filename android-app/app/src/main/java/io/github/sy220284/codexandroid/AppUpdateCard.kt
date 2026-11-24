package io.github.sy220284.codexandroid

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 版本和进度始终留在设置卡片，具体更新操作在独立底部面板中进行。 */
@Composable
internal fun AppUpdateCard(vm: CodexViewModel, autoExpand: Boolean = false) {
    val status by AppUpdateManager.state.collectFrameState()
    val activity = LocalActivity.current
    val work = observeGlobalWorkSnapshot(vm)
    val currentVersion = remember(activity) {
        activity?.packageManager?.getPackageInfo(activity.packageName, 0)?.versionName.orEmpty()
    }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoExpand) { if (autoExpand) showSheet = true }
    val busy = status.phase in setOf(UpdatePhase.CHECKING,
        UpdatePhase.DOWNLOADING, UpdatePhase.PATCHING)

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("settings-update"),
        shape = RoundedCornerShape(22.dp),
        color = Ink.canvas,
        border = BorderStroke(1.dp, Ink.border),
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable { showSheet = true }
                    .testTag("settings-update-toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CodexIcon(CodexGlyph.SystemUpdate, contentDescription = null,
                    tint = Ink.copper)
                Column(Modifier.weight(1f)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium)
                    Text("当前版本：${currentVersion.ifBlank { "未知" }}",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                CodexIcon(CodexGlyph.ChevronRight, contentDescription = "查看更新操作",
                    tint = Ink.muted)
            }
            if (status.phase != UpdatePhase.IDLE) {
                AtlasUpdateStatus(status, Modifier.testTag("update-inline-status"),
                    progressTag = "update-inline-progress")
            }
        }
    }
    if (showSheet) {
        CodexDetailSheet(
            title = "应用更新",
            subtitle = "检查、下载和安装均使用当前设备上的真实更新状态",
            onDismiss = { showSheet = false }
        ) {
            Text("当前版本：${currentVersion.ifBlank { "未知" }}",
                style = MaterialTheme.typography.titleMedium)
            if (status.targetVersion.isNotBlank()) {
                Text("目标版本：${status.targetVersion}", style = MaterialTheme.typography.bodyMedium)
            }
            AtlasUpdateStatus(status, Modifier.testTag("update-status"),
                progressTag = "update-progress")
            CodexPrimaryButton(
                onClick = { activity?.let(AppUpdateManager::checkAndDownload) },
                enabled = activity != null && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("update-check")
            ) { Text(if (busy) "正在处理" else "检查并下载更新") }
            if (busy) {
                CodexSecondaryButton(
                    onClick = AppUpdateManager::cancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (status.phase == UpdatePhase.DOWNLOADING) "取消下载" else "取消当前操作") }
            }
            if (status.phase in setOf(UpdatePhase.READY,
                    UpdatePhase.NEEDS_PERMISSION, UpdatePhase.INSTALLING)) {
                CodexSecondaryButton(
                    onClick = {
                        val current = vm.globalWorkSnapshot()
                        if (current.canDisruptCoreOrData) {
                            activity?.let(AppUpdateManager::install)
                        } else {
                            vm.errors.value = vm.errors.value +
                                ("update" to current.blockReason)
                        }
                    },
                    enabled = activity != null && work.canDisruptCoreOrData,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("update-install")
                ) { Text(if (status.phase == UpdatePhase.INSTALLING) "重新打开安装界面" else "安装更新") }
                if (!work.canDisruptCoreOrData) {
                    Text(work.blockReason, color = Ink.warning,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


/** 状态由真实更新器驱动，下载显示实际比例，校验及检查只显示不定进度。 */
@Composable
internal fun AtlasUpdateStatus(status: AppUpdateState, modifier: Modifier = Modifier,
    progressTag: String = "update-progress") {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when (status.phase) {
            UpdatePhase.DOWNLOADING -> AtlasOperationProgress(status.message,
                Modifier.testTag(progressTag), progress = status.progress / 100f)
            UpdatePhase.CHECKING, UpdatePhase.PATCHING -> AtlasOperationProgress(status.message,
                Modifier.testTag(progressTag))
            UpdatePhase.ERROR -> CodexNotice(status.message, NoticeTone.Error)
            UpdatePhase.READY, UpdatePhase.UP_TO_DATE -> CodexNotice(status.message, NoticeTone.Success)
            UpdatePhase.NEEDS_PERMISSION -> CodexNotice(status.message, NoticeTone.Warning)
            UpdatePhase.INSTALLING, UpdatePhase.IDLE -> CodexNotice(status.message)
        }
    }
}
