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

/**
 * A shared reactive projection for every UI that can interrupt core work or
 * touch the process-wide local archive. All six inputs trigger recomposition.
 */
@Composable
internal fun observeGlobalWorkSnapshot(vm: CodexViewModel): GlobalWorkSnapshot {
    val visible by vm.running.collectAsState()
    val executions by vm.threadExecutions.collectAsState()
    val creating by vm.firstThreadStarting.collectAsState()
    val terminal by vm.terminalSession.collectAsState()
    val approvals by vm.bridge.prompts.collectAsState()
    val transfer by AppDataTransfer.state.collectAsState()
    val requests by vm.loading.collectAsState()
    val login by vm.loginBusy.collectAsState()
    val planApproval by vm.planApprovalBusy.collectAsState()
    return GlobalWorkSnapshot(
        visibleTurnRunning = visible,
        executingThreads = executions.size,
        creatingThread = creating,
        terminalRunning = terminal.running,
        pendingApprovals = approvals.size,
        dataTransferBusy = transfer.busy,
        inFlightRequests = requests.size,
        loginBusy = login,
        planApprovalBusy = planApproval
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
                CircularProgressIndicator()
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
