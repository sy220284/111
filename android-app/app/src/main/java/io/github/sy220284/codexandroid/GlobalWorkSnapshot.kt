package io.github.sy220284.codexandroid

/**
 * Process-wide safety boundary for operations that can replace local data or
 * terminate the core. The currently visible chat is only one of many threads.
 *
 * Keep normal per-thread send/stop behavior separate from this global gate.
 */
internal data class GlobalWorkSnapshot(
    val visibleTurnRunning: Boolean,
    val executingThreads: Int,
    val creatingThread: Boolean,
    val terminalRunning: Boolean,
    val pendingApprovals: Int,
    val dataTransferBusy: Boolean
) {
    val hasActiveWork: Boolean
        get() = visibleTurnRunning || executingThreads > 0 ||
            creatingThread || terminalRunning || pendingApprovals > 0

    val canDisruptCoreOrData: Boolean
        get() = !hasActiveWork && !dataTransferBusy

    val canStartNewTask: Boolean
        get() = !dataTransferBusy

    val blockReason: String
        get() = when {
            dataTransferBusy -> "本机数据正在备份或恢复，请完成后再操作"
            hasActiveWork -> "仍有会话任务、终端命令或审批待处理，请完成后再操作"
            else -> ""
        }
}

/** Read the live flows at the time of the action; never rely only on UI button state. */
internal fun CodexViewModel.globalWorkSnapshot(): GlobalWorkSnapshot = GlobalWorkSnapshot(
    visibleTurnRunning = running.value,
    executingThreads = threadExecutions.value.size,
    creatingThread = firstThreadStarting.value,
    terminalRunning = terminalSession.value.running,
    pendingApprovals = bridge.prompts.value.size,
    dataTransferBusy = AppDataTransfer.state.value.busy
)
