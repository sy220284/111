package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-owned media lease; UI recreation and failed stop cannot hide active work. */
internal class RealtimeVoiceLease {
    private var owner: Any? = null
    private var retry: (() -> Unit)? = null
    private val activeState = MutableStateFlow(false)
    private val failureState = MutableStateFlow<String?>(null)
    val active = activeState.asStateFlow()
    val stopFailure = failureState.asStateFlow()

    @Synchronized fun acquire(candidate: Any): Boolean {
        if (owner != null) return false
        owner = candidate
        activeState.value = true
        return true
    }

    @Synchronized fun markFailed(candidate: Any, reason: String, onRetry: () -> Unit) {
        if (owner !== candidate) return
        retry = onRetry
        failureState.value = reason
    }

    @Synchronized fun clearFailure(candidate: Any) {
        if (owner !== candidate) return
        retry = null
        failureState.value = null
    }

    fun retryFailedStop() {
        val action = synchronized(this) { retry }
        action?.invoke()
    }

    @Synchronized fun release(candidate: Any) {
        if (owner !== candidate) return
        clearFailure(candidate)
        owner = null
        activeState.value = false
    }
}
