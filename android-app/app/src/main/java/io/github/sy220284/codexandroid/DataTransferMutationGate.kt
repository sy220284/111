package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Central mutation boundary while an app-local archive owns the filesystem.
 * A screen-level dialog prevents clicks; this guard also covers stale sheets
 * and already-created UI callbacks outside the current Compose tree.
 */
internal object DataTransferMutationGate {
    fun refuse(vm: CodexViewModel, key: String, method: String): Boolean {
        if (vm.globalWorkSnapshot().canStartNewTask) return false
        val reason = "本机备份或恢复正在进行，完成后再操作"
        vm.errors.value = vm.errors.value + (key to reason)
        vm.viewModelScope.launch {
            vm.actionFeedback.emit(ActionFeedbackCopy.failure(method, reason))
        }
        return true
    }
}
