package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All user-triggered restarts respect active work before disposing the native child. */
internal object ConnectionRestartPolicy {
    fun canRestart(running: Boolean, terminalRunning: Boolean, approvals: Int): Boolean =
        !running && !terminalRunning && approvals == 0
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

    fun restart() {
        if (!ConnectionRestartPolicy.canRestart(
                vm.running.value, vm.terminalSession.value.running,
                vm.bridge.prompts.value.size)) {
            vm.errors.value = vm.errors.value + ("diagnostics" to
                "仍有任务或审批待处理，请先完成操作再重启连接")
            return
        }
        if (!busy.compareAndSet(false, true)) return
        vm.viewModelScope.launch {
            try {
                check(ConnectionRestartPolicy.canRestart(
                    vm.running.value, vm.terminalSession.value.running,
                    vm.bridge.prompts.value.size)) { "任务状态已变化，内核重启已取消" }
                withContext(Dispatchers.IO) { vm.bridge.close() }
                vm.connect().join()
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
