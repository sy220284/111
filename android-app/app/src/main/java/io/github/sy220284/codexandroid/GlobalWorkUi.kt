package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

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
    return GlobalWorkSnapshot(
        visibleTurnRunning = visible,
        executingThreads = executions.size,
        creatingThread = creating,
        terminalRunning = terminal.running,
        pendingApprovals = approvals.size,
        dataTransferBusy = transfer.busy
    )
}
