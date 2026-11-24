package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All user-triggered restarts respect active work before disposing the native child. */
internal object ConnectionRestartPolicy {
    fun canRestart(snapshot: GlobalWorkSnapshot): Boolean =
        snapshot.canDisruptCoreOrData

    /** Only a failed voice stop may be retired by an explicitly confirmed recovery. */
    fun canRecoverVoice(snapshot: GlobalWorkSnapshot, failed: Boolean): Boolean =
        failed && snapshot.realtimeVoiceActive && snapshot.copy(realtimeVoiceActive = false).canDisruptCoreOrData

    /** The caller already owns the restart lease. Ignore only that lease while
     * rechecking unrelated work immediately before disposing the core.
     */
    fun canContinueOwnedRestart(snapshot: GlobalWorkSnapshot): Boolean =
        snapshot.copy(restarting = false).canDisruptCoreOrData
}

internal class ConnectionRestartController(private val vm: CodexViewModel) {
    val busy = MutableStateFlow(false)

    fun reconnect() {
        if (vm.bridge.status.value == EngineStatus.Connected) return
        if (!busy.compareAndSet(false, true)) return
        vm.viewModelScope.launch {
            try {
                vm.connect().join()
            } finally {
                busy.value = false
            }
        }
    }

    fun restart() = restart(recoverVoice = false)

    fun restartAfterVoiceFailure() = restart(recoverVoice = true)

    private fun restart(recoverVoice: Boolean) {
        fun allowed(snapshot: GlobalWorkSnapshot): Boolean = if (recoverVoice) {
            ConnectionRestartPolicy.canRecoverVoice(snapshot, vm.realtimeVoiceLease.stopFailure.value != null)
        } else ConnectionRestartPolicy.canRestart(snapshot)
        if (!allowed(vm.globalWorkSnapshot())) {
            vm.errors.value = vm.errors.value + ("diagnostics" to
                "仍有任务或审批待处理，请先完成操作再重启连接")
            return
        }
        if (!busy.compareAndSet(false, true)) return
        vm.viewModelScope.launch {
            try {
                val current = vm.globalWorkSnapshot()
                check(if (recoverVoice) allowed(current.copy(restarting = false))
                    else ConnectionRestartPolicy.canContinueOwnedRestart(current)) {
                    "任务或数据备份状态已变化，内核重启已取消"
                }
                withContext(Dispatchers.IO) { vm.bridge.close() }
                vm.connect().join()
                check(vm.bridge.status.value == EngineStatus.Connected) {
                    (vm.bridge.status.value as? EngineStatus.Unavailable)?.reason ?: "重新连接尚未完成"
                }
                vm.errors.value = vm.errors.value - "diagnostics"
            } catch (failure: Exception) {
                vm.errors.value = vm.errors.value + ("diagnostics" to
                    (failure.message ?: "重启内核连接失败"))
            } finally {
                busy.value = false
            }
        }
    }
}
