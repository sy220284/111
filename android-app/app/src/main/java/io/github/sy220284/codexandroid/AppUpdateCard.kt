package io.github.sy220284.codexandroid

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 设置中心内的应用更新入口；检查后会自动下载并引导安装。 */
@Composable
internal fun AppUpdateCard() {
    val status by AppUpdateManager.state.collectAsState()
    val activity = LocalActivity.current
    val busy = status.phase in setOf(
        UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.PATCHING
    )
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("settings-update"),
        shape = RoundedCornerShape(25.dp),
        color = Ink.canvas,
        border = BorderStroke(1.dp, Ink.border.copy(alpha = .72f)),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.SystemUpdate, null, tint = Ink.copper)
                Column(modifier = Modifier.weight(1f)) {
                    Text("应用更新", style = MaterialTheme.typography.titleMedium)
                    Text("检查正式版 · 优先增量下载",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(status.message, color = Ink.muted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("update-status"))
            if (status.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { status.progress / 100f },
                    modifier = Modifier.fillMaxWidth().testTag("update-progress")
                )
            }
            if (status.phase == UpdatePhase.CHECKING || status.phase == UpdatePhase.PATCHING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { activity?.let(AppUpdateManager::checkAndDownload) },
                    enabled = activity != null && !busy,
                    modifier = Modifier.testTag("update-check"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (busy) "更新处理中" else "检查更新")
                }
                if (busy) {
                    TextButton(onClick = AppUpdateManager::cancel) { Text("取消") }
                }
                if (status.phase in setOf(
                        UpdatePhase.READY, UpdatePhase.NEEDS_PERMISSION, UpdatePhase.INSTALLING
                    )) {
                    Button(
                        onClick = { activity?.let(AppUpdateManager::install) },
                        enabled = activity != null,
                        modifier = Modifier.testTag("update-install"),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("安装更新") }
                }
            }
        }
    }
}
