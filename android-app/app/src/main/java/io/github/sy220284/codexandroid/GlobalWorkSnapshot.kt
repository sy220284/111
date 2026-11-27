package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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
    val dataTransferBusy: Boolean,
    val inFlightRequests: Int = 0,
    val loginBusy: Boolean = false,
    val planApprovalBusy: Boolean = false,
    val switchingThread: Boolean = false,
    val terminalStarting: Boolean = false,
    val securitySaveBusy: Boolean = false,
    val coreStarting: Boolean = false,
    val restarting: Boolean = false,
    val realtimeVoiceActive: Boolean = false,
    val inFlightUserRequests: Int = 0,
    val localMutations: Int = 0,
    val pendingRestore: Boolean = false,
    val screenShareActive: Boolean = false,
    val mediaAnalysisActive: Boolean = false,
    val liveScreenAnalysisActive: Boolean = false
) {
    val hasActiveWork: Boolean
        get() = visibleTurnRunning || executingThreads > 0 ||
            creatingThread || terminalRunning || pendingApprovals > 0 ||
            inFlightRequests > 0 || localMutations > 0 || loginBusy || planApprovalBusy ||
            switchingThread || terminalStarting || securitySaveBusy || coreStarting || restarting || realtimeVoiceActive || screenShareActive || mediaAnalysisActive || liveScreenAnalysisActive

    /** Foreground protection tracks deliberate work, not passive catalog polling. */
    val requiresForegroundProtection: Boolean
        get() = visibleTurnRunning || executingThreads > 0 || creatingThread || terminalRunning ||
            terminalStarting || pendingApprovals > 0 || realtimeVoiceActive || loginBusy ||
            planApprovalBusy || securitySaveBusy || switchingThread || inFlightUserRequests > 0 || localMutations > 0 || dataTransferBusy || mediaAnalysisActive || liveScreenAnalysisActive

    val canDisruptCoreOrData: Boolean
        get() = !hasActiveWork && !dataTransferBusy && !pendingRestore

    val canStartNewTask: Boolean
        get() = !dataTransferBusy && !pendingRestore

    val blockReason: String
        get() = when {
            pendingRestore -> "备份已验证，完成或取消恢复后即可继续操作"
            dataTransferBusy -> "本机数据正在备份或恢复，请完成后再操作"
            localMutations > 0 -> "本机文件或草稿正在保存，请完成后再备份、恢复或重启"
            inFlightRequests > 0 || loginBusy || planApprovalBusy ||
                switchingThread || terminalStarting || securitySaveBusy || coreStarting || restarting ->
                "请求或授权操作正在处理，请完成后再备份、恢复或重启"
            realtimeVoiceActive -> "实时语音尚未结束，请先结束或恢复语音连接"
            mediaAnalysisActive || liveScreenAnalysisActive -> "媒体分析仍在进行，请先结束分析"
            screenShareActive -> "屏幕共享尚未结束，请先停止共享"
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
    dataTransferBusy = AppDataTransfer.state.value.busy,
    inFlightRequests = loading.value.size + bridge.activeRpcRequests.value.size + if (bridge.writing.value) 1 else 0,
    loginBusy = loginBusy.value,
    planApprovalBusy = planApprovalBusy.value,
    switchingThread = threadSwitching.value,
    terminalStarting = terminalStarting.value,
    securitySaveBusy = securitySaveBusy.value,
    coreStarting = bridge.status.value == EngineStatus.Starting || bridge.retirementBusy.value,
    restarting = connectionRestarter.busy.value,
    realtimeVoiceActive = realtimeVoiceLease.active.value,
    inFlightUserRequests = bridge.userRpcRequests.value,
    localMutations = AppDataTransfer.localMutations.value,
    pendingRestore = AppDataTransfer.state.value.pendingRestore,
    screenShareActive = screenShareSession.state.value.token != null,
    mediaAnalysisActive = composerContent.mediaAnalysis.state.value.busy,
    liveScreenAnalysisActive = composerContent.liveScreenAnalysis.state.value.running
)


/** The same live state feeds maintenance, service notification and Compose. */
internal fun CodexViewModel.globalWorkChanges(): Flow<GlobalWorkSnapshot> {
    val sources: Array<Flow<Any?>> = arrayOf(
        running, threadExecutions, firstThreadStarting, terminalSession, bridge.prompts,
        AppDataTransfer.state, AppDataTransfer.localMutations, loading, bridge.activeRpcRequests, bridge.writing, bridge.userRpcRequests,
        loginBusy, planApprovalBusy, threadSwitching, terminalStarting, securitySaveBusy,
        bridge.status, bridge.retirementBusy, connectionRestarter.busy, realtimeVoiceLease.active, screenShareSession.state, composerContent.mediaAnalysis.state, composerContent.liveScreenAnalysis.state
    )
    return combine(*sources) { globalWorkSnapshot() }.distinctUntilChanged()
}
