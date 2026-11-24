package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
    val visible by vm.running.collectAsState()
    val executions by vm.threadExecutions.collectProjection { it.size }
    val creating by vm.firstThreadStarting.collectAsState()
    val terminalRunning by vm.terminalSession.collectProjection { it.running }
    val approvals by vm.bridge.prompts.collectProjection { it.size }
    val transferBusy by AppDataTransfer.state.collectProjection { it.busy }
    val requests by vm.loading.collectProjection { it.size }
    val wireRequests by vm.bridge.activeRpcRequests.collectProjection { it.size }
    val login by vm.loginBusy.collectAsState()
    val planApproval by vm.planApprovalBusy.collectAsState()
    val threadSwitching by vm.threadSwitching.collectAsState()
    val terminalStarting by vm.terminalStarting.collectAsState()
    val securitySave by vm.securitySaveBusy.collectAsState()
    val coreStatus by vm.bridge.status.collectAsState()
    val restarting by vm.connectionRestarter.busy.collectAsState()
    return GlobalWorkSnapshot(
        visibleTurnRunning = visible,
        executingThreads = executions,
        creatingThread = creating,
        terminalRunning = terminalRunning,
        pendingApprovals = approvals,
        dataTransferBusy = transferBusy,
        inFlightRequests = requests + wireRequests,
        loginBusy = login,
        planApprovalBusy = planApproval,
        switchingThread = threadSwitching,
        terminalStarting = terminalStarting,
        securitySaveBusy = securitySave,
        coreStarting = coreStatus == EngineStatus.Starting,
        restarting = restarting
    )
}

/**
 * A real window-level modal prevents edits from every page while an archive is
 * being written or verified. Individual buttons cannot ensure a consistent
 * snapshot because workspace editors, settings and child sheets mutate data.
 */
@Composable
internal fun DataTransferBusyOverlay(transfer: DataTransferState) {
    if (!transfer.busy) return
    val context = LocalContext.current
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
                CodexActivityIndicator()
                Text(
                    text = transfer.result.ifBlank { "正在处理本机工作数据…" },
                    modifier = Modifier.testTag("data-transfer-busy-message"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "请保持应用在前台，处理完成后即可继续编辑项目、发送指令和调整设置。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Keep changing archive progress in its own overlay rather than invalidating the page shell. */
@Composable
internal fun ObserveDataTransferBusyOverlay() {
    val transfer by AppDataTransfer.state.collectAsState()
    DataTransferBusyOverlay(transfer)
}
