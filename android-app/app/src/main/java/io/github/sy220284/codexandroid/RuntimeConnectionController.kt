package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** All Activity, Service and explicit reconnect callers share a single job. */
internal class RuntimeConnectionController(private val vm: CodexViewModel) {
    private var connectJob: Job? = null
    private var synchronizedEpoch: Long? = null
    private var recoveryJob: Job? = null

    @Synchronized fun connect(): Job {
        connectJob?.takeIf { it.isActive }?.let { return it }
        val job = vm.viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                vm.bridge.connect()
                val epoch = vm.bridge.connectedSession.value ?: return@launch
                if (synchronizedEpoch == epoch) return@launch
                val previousThread = vm.activeThread.value
                vm.threadOrganization.invalidate()
                vm.invalidateAccountReceipts()
                vm.sessions.reconnect()
                vm.mcpCatalog.reset()
                vm.modelPreviews.clear()
                vm.threadActivities.value = emptyMap()
                vm.errors.value = vm.errors.value - "engine" - "protocol"
                vm.refresh("account", "account/read", JSONObject())
                vm.syncRecentThreads()
                vm.extensions.invalidate(preserveAuthorization = true)
                vm.extensions.refreshAfterAppAuthorization()
                vm.catalogWarmup.connected()
                vm.accountMetrics.accountChanged()
                // Recover authoritative history; never replay a mutation or a prompt.
                recoveryJob?.cancel()
                if (!previousThread.isNullOrBlank()) recoveryJob = vm.viewModelScope.launch {
                    // The old connection's history/selection finally blocks must finish
                    // before recovery takes over. A deliberate new selection supersedes it.
                    combine<Any?, Boolean>(vm.threadSwitching, vm.loadingEarlier, vm.timelineCompaction,
                        vm.firstThreadStarting, vm.running, vm.activeThread, vm.bridge.connectedSession) {
                        vm.bridge.connectedSession.value != epoch || vm.activeThread.value != previousThread ||
                            (!vm.threadSwitching.value && !vm.loadingEarlier.value &&
                                !vm.timelineCompacting && !vm.firstThreadStarting.value && !vm.running.value)
                    }.first { it }
                    if (vm.bridge.connectedSession.value == epoch && vm.activeThread.value == previousThread)
                        vm.chooseThread(previousThread, forceReload = true)
                }
                synchronizedEpoch = epoch
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                vm.errors.value = vm.errors.value + ("engine" to (failure.message ?: "无法连接运行核心"))
            }
        }
        connectJob = job
        job.start()
        return job
    }

    fun reconnectIfNeeded() {
        if ((vm.bridge.status.value as? EngineStatus.Unavailable)?.recoverable == true && !vm.bridge.retirementBusy.value &&
            !AppDataTransfer.state.value.busy && !vm.connectionRestarter.busy.value) connect()
    }
}
