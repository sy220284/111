package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One reactive projection for all core/data disruption controls. */
@Composable
internal fun observeGlobalWorkSnapshot(vm: CodexViewModel): GlobalWorkSnapshot {
    val changes = remember(vm) { vm.globalWorkChanges() }
    val snapshot by changes.collectAsState(initial = vm.globalWorkSnapshot())
    return snapshot
}

/**
 * A real window-level modal prevents edits from every page while an archive is
 * being written or verified. Individual buttons cannot ensure a consistent
 * snapshot because workspace editors, settings and child sheets mutate data.
 */
@Composable
internal fun DataTransferBusyOverlay(transfer: DataTransferState, vm: CodexViewModel? = null) {
    if (!transfer.busy) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Dialog(
        // Let back/outside gestures be acknowledged while retaining the modal:
        // the archive is still writing and dismissing it would expose edit actions.
        onDismissRequest = {
            Toast.makeText(
                context,
                "本机数据正在处理，完成后即可继续操作",
                Toast.LENGTH_SHORT
            ).show()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("data-transfer-busy-overlay"),
            shape = RoundedCornerShape(20.dp),
            color = Ink.canvas
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!transfer.pendingRestore || closing) CodexActivityIndicator()
                Text(
                    text = transfer.result.ifBlank { "正在处理本机工作数据…" },
                    modifier = Modifier.testTag("data-transfer-busy-message"),
                    style = MaterialTheme.typography.titleMedium
                )
                if (transfer.pendingRestore) {
                    Text("备份已经暂存；恢复前暂停新任务和文件写入，避免覆盖新数据。",
                        style = MaterialTheme.typography.bodyMedium)
                    failure?.let { ErrorText(it) }
                    CodexPrimaryButton(onClick = {
                        val owner = vm ?: return@CodexPrimaryButton
                        closing = true
                        failure = null
                        scope.launch {
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { owner.bridge.close() }
                                kotlinx.coroutines.withTimeout(15_000) {
                                    owner.bridge.retirementBusy.first { !it }
                                }
                                check(owner.bridge.connectedSession.value == null) { "旧内核尚未退出" }
                                context.stopService(android.content.Intent(context, CodexForegroundService::class.java))
                                // Explicit user action only, after the old core has exited.
                                // Next launch applies the verified archive before constructing the ViewModel.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            } catch (error: Exception) {
                                failure = error.message ?: "无法关闭应用，请完全退出后重新打开"
                                closing = false
                            }
                        }
                    }, enabled = !closing && vm != null, modifier = Modifier.fillMaxWidth().testTag("restore-close-app")) {
                        Text(if (closing) "正在关闭应用" else "关闭应用以恢复")
                    }
                    CodexSecondaryButton(onClick = { AppDataTransfer.cancelPending(context) },
                        enabled = !closing, modifier = Modifier.fillMaxWidth().testTag("restore-cancel-pending")) {
                        Text("取消本次恢复")
                    }
                } else Text(
                    text = "请保持应用在前台，处理完成后即可继续编辑项目、发送指令和调整设置。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Keep changing archive progress in its own overlay rather than invalidating the page shell. */
@Composable
internal fun ObserveDataTransferBusyOverlay(vm: CodexViewModel) {
    val transfer by AppDataTransfer.state.collectAsState()
    DataTransferBusyOverlay(transfer, vm)
}

/** A failed stop survives navigation and remains actionable on every page. */
@Composable
internal fun RealtimeVoiceRecoveryNotice(vm: CodexViewModel) {
    val failure by vm.realtimeVoiceLease.stopFailure.collectAsState()
    if (failure == null) return
    val work = observeGlobalWorkSnapshot(vm)
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        CodexNotice("实时语音尚未确认结束：" + failure, NoticeTone.Error,
            actionLabel = "重试结束", onAction = { vm.realtimeVoiceLease.retryFailedStop() })
        CodexTextAction(onClick = { confirm = true },
            enabled = ConnectionRestartPolicy.canRecoverVoice(work, true)) { Text("重新连接内核以恢复语音") }
    }
    if (confirm) CodexConfirmDialog("恢复语音连接",
        "将关闭旧的运行核心并重新连接。仅在其他任务、审批和数据操作全部结束后执行。",
        confirmLabel = "重新连接", onConfirm = {
            confirm = false
            vm.connectionRestarter.restartAfterVoiceFailure()
        }, onDismiss = { confirm = false })
}

/** A failed Android foreground promotion remains visible across all pages. */
@Composable
internal fun RuntimeProtectionNotice(vm: CodexViewModel) {
    val errors by vm.errors.collectAsState()
    val failure = errors["foregroundProtection"] ?: return
    val work = observeGlobalWorkSnapshot(vm)
    if (!work.requiresForegroundProtection) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        CodexNotice(failure, NoticeTone.Error, actionLabel = "重试后台保护", onAction = {
            scope.launch { ensureForegroundProtection(context, vm) }
        })
    }
}
