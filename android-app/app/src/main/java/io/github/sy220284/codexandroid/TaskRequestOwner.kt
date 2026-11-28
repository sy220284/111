package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Immutable ownership of an admitted task; never silently move it to a new account or core. */
internal data class TaskRequestOwner(val epoch: Long, val account: Long) {
    fun matches(currentEpoch: Long?, currentAccount: Long): Boolean =
        currentEpoch == epoch && currentAccount == account

    fun current(vm: CodexViewModel): Boolean =
        matches(vm.bridge.connectedSession.value, vm.catalogWarmup.accountRevision.value)

    fun requireCurrent(vm: CodexViewModel) {
        check(current(vm)) { "操作所属连接或账号已变化，请核对原任务后重试" }
    }

    suspend fun request(vm: CodexViewModel, method: String, params: JSONObject? = null): JSONObject {
        return guarded(vm) {
            vm.bridge.request(method, params, expectedSession = epoch, sendPermit = { current(vm) })
        }
    }

    suspend fun request(vm: CodexViewModel, method: String, params: JSONObject?, timeoutMillis: Long?): JSONObject =
        guarded(vm) {
            vm.bridge.request(method, params, timeoutMillis = timeoutMillis,
                expectedSession = epoch, sendPermit = { current(vm) })
        }

    private suspend fun <T> guarded(vm: CodexViewModel, execute: suspend () -> T): T {
        requireCurrent(vm)
        val response = execute()
        requireCurrent(vm)
        return response
    }
}

internal fun CodexViewModel.captureTaskOwner(): TaskRequestOwner? =
    bridge.connectedSession.value?.let { TaskRequestOwner(it, catalogWarmup.accountRevision.value) }
