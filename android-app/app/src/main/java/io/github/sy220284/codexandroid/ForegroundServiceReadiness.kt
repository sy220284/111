package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Android's startForegroundService() only schedules creation; it does not mean
 * startForeground() has succeeded. Keep that result explicit for terminal work.
 */
internal sealed interface ForegroundStartState {
    data object Stopped : ForegroundStartState
    data object Starting : ForegroundStartState
    data object Ready : ForegroundStartState
    data class Failed(val reason: String) : ForegroundStartState
}

internal class ForegroundServiceReadiness {
    val state = MutableStateFlow<ForegroundStartState>(ForegroundStartState.Stopped)

    fun begin() {
        if (state.value != ForegroundStartState.Ready)
            state.value = ForegroundStartState.Starting
    }

    fun ready() { state.value = ForegroundStartState.Ready }

    fun failed(reason: String) { state.value = ForegroundStartState.Failed(reason) }

    fun stopped() {
        // Do not erase an error before a waiting caller has observed it.
        if (state.value !is ForegroundStartState.Failed)
            state.value = ForegroundStartState.Stopped
    }

    suspend fun awaitReady(timeoutMillis: Long = 12_000L) {
        when (val result = withTimeout(timeoutMillis) {
            state.first { it is ForegroundStartState.Ready || it is ForegroundStartState.Failed }
        }) {
            ForegroundStartState.Ready -> Unit
            is ForegroundStartState.Failed -> throw IllegalStateException(result.reason)
            else -> error("前台服务就绪状态异常")
        }
    }
}
