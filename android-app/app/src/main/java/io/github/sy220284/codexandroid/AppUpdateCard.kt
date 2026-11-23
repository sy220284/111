package io.github.sy220284.codexandroid

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 设置中默认仅一行；实际检查、下载、校验和安装始终使用原有 AppUpdateManager。 */
@Composable
internal fun AppUpdateCard(vm: CodexViewModel, autoExpand: Boolean = false) {
    val status by AppUpdateManager.state.collectAsState()
    val activity = LocalActivity.current
    val work = observeGlobalWorkSnapshot(vm)
    val currentVersion = remember(activity) {
        activity?.packageManager?.getPackageInfo(activity.packageName, 0)?.versionName.orEmpty()
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoExpand) { if (autoExpand) expanded = true }
    val busy = status.phase in setOf(UpdatePhase.CHECKING,
        UpdatePhase.DOWNLOADING, UpdatePhase.PATCHING)
    val showDetails = expanded || busy || status.phase in setOf(
        UpdatePhase.READY, UpdatePhase.ERROR, UpdatePhase.NEEDS_PERMISSION,
        UpdatePhase.INSTALLING)
    Surface(modifier = Modifier.fillMaxWidth().testTag("settings-update"),
        shape = RoundedCornerShape(24.dp), color = Ink.canvas,
        border = BorderStroke(1.dp, Ink.border.copy(alpha = .72f)),
        shadowElevation = 2.dp, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .testTag("settings-update-toggle").heightIn(min = 49.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.SystemUpdate, null, tint = Ink.copper)
                Column(Modifier.weight(1f)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium)
                    Text("当前版本：${currentVersion.ifBlank { "未知" }}",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                    contentDescription = if (showDetails) "收起更新信息" else "展开更新操作",
                    tint = Ink.muted)
            }
            CodexReveal(showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    HorizontalDivider(color = Ink.border)
                    Text(status.message, color = Ink.muted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("update-status"))
                    if (status.phase == UpdatePhase.DOWNLOADING)
                        LinearProgressIndicator(progress = { status.progress / 100f },
                            modifier = Modifier.fillMaxWidth().testTag("update-progress"),
                            color = Ink.copper, trackColor = Ink.border)
                    if (status.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.PATCHING))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { activity?.let(AppUpdateManager::checkAndDownload) },
                            enabled = activity != null && !busy,
                            modifier = Modifier.testTag("update-check"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (busy) "正在处理" else "检查并下载更新")
                        }
                        if (busy) TextButton(onClick = AppUpdateManager::cancel) { Text("取消") }
                        if (status.phase in setOf(UpdatePhase.READY,
                                UpdatePhase.NEEDS_PERMISSION, UpdatePhase.INSTALLING)) {
                            OutlinedButton(
                                onClick = {
                                    if (work.canDisruptCoreOrData) {
                                        activity?.let(AppUpdateManager::install)
                                    } else {
                                        vm.errors.value = vm.errors.value + (
                                            "update" to work.blockReason)
                                    }
                                },
                                enabled = activity != null && work.canDisruptCoreOrData,
                                modifier = Modifier.testTag("update-install"),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("安装更新") }
                        }
                    }
                    if (status.phase in setOf(
                            UpdatePhase.READY, UpdatePhase.NEEDS_PERMISSION,
                            UpdatePhase.INSTALLING) && !work.canDisruptCoreOrData) {
                        Text(work.blockReason, color = Ink.warning,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
