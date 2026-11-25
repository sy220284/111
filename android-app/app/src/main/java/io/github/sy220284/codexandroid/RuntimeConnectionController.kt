package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** All Activity, Service and explicit reconnect callers share a single job. */
internal class RuntimeConnectionController(private val vm: CodexViewModel) {
    private var connectJob: Job? = null
    private var synchronizedEpoch: Long? = null

    @Synchronized fun connect(): Job {
        connectJob?.takeIf { it.isActive }?.let { return it }
        val job = vm.viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                vm.bridge.connect()
                val epoch = vm.bridge.connectedSession.value ?: return@launch
                if (synchronizedEpoch == epoch) return@launch
                val previousThread = vm.activeThread.value
                vm.threadOrganization.invalidate()
                vm.mcpCatalog.reset()
                vm.modelPreviews.clear()
                vm.threadActivities.value = emptyMap()
                vm.errors.value = vm.errors.value - "engine" - "protocol"
                vm.refresh("account", "account/read", JSONObject())
                vm.refresh("models", "model/list", JSONObject())
                vm.syncRecentThreads()
                vm.extensions.invalidate(preserveAuthorization = true)
                vm.extensions.refreshAfterAppAuthorization()
                vm.catalogWarmup.connected()
                vm.accountMetrics.accountChanged()
                // Recover authoritative history; never replay a mutation or a prompt.
                if (!previousThread.isNullOrBlank() && !vm.running.value)
                    vm.chooseThread(previousThread, forceReload = true)
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
