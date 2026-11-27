package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow

/** One process owner for foreground protection, failure recovery and idle release.
 * No periodic polling while the engine is healthy or the process is idle. */
internal class RuntimeSupervisor(
    private val context: Context,
    private val vm: CodexViewModel,
    private val foreground: StateFlow<Boolean>,
    scope: CoroutineScope
) {
    private val work = vm.globalWorkChanges()

    init {
        scope.launch {
            combine(foreground, work, vm.bridge.status) { visible, snapshot, status ->
                !visible && snapshot.canDisruptCoreOrData && status == EngineStatus.Connected
            }.distinctUntilChanged().collectLatest { idle ->
                if (!idle) return@collectLatest
                delay(90_000)
                val retired = withContext(NonCancellable + Dispatchers.IO) {
                    vm.bridge.closeIfIdle {
                        !foreground.value && vm.globalWorkSnapshot().canDisruptCoreOrData
                    }
                }
                if (retired && foreground.value) vm.reconnectIfNeeded()
            }
        }
        scope.launch {
            val eligible = combine(foreground, work, vm.bridge.status) { visible, snapshot, status ->
                RuntimeRecoveryPolicy.shouldRecover(visible, snapshot, (status as? EngineStatus.Unavailable)?.recoverable == true)
            }.distinctUntilChanged()
            combine(foreground, work) { visible, snapshot ->
                (visible || snapshot.requiresForegroundProtection) && snapshot.canStartNewTask && !snapshot.restarting
            }.distinctUntilChanged().collectLatest { demand ->
                if (!demand) return@collectLatest
                var interval = 1_000L
                try {
                    while (true) {
                        eligible.first { it }
                        vm.recoveryDelayMs.value = interval
                        val due = RuntimeRecoveryPolicy.waitForRetry(eligible, interval)
                        vm.recoveryDelayMs.value = null
                        if (!due) {
                            // A manual reconnect, a core retirement or a pause ends this countdown.
                            interval = 1_000L
                            continue
                        }
                        if (!RuntimeRecoveryPolicy.shouldRecover(foreground.value, vm.globalWorkSnapshot(),
                                (vm.bridge.status.value as? EngineStatus.Unavailable)?.recoverable == true)) continue
                        vm.connect().join()
                        interval = if (vm.bridge.status.value == EngineStatus.Connected) 1_000L
                            else (interval * 2).coerceAtMost(60_000L)
                    }
                } finally { vm.recoveryDelayMs.value = null }
            }
        }
        scope.launch {
            combine(foreground, work) { visible, snapshot ->
                visible && snapshot.requiresForegroundProtection
            }.distinctUntilChanged().collectLatest { protect ->
                if (!protect) return@collectLatest
                ensureForegroundProtection(context, vm)
            }
        }
    }
}

internal object RuntimeRecoveryPolicy {
    suspend fun waitForRetry(eligible: Flow<Boolean>, delayMillis: Long): Boolean =
        withTimeoutOrNull(delayMillis) { eligible.first { !it } } == null

    fun shouldRecover(visible: Boolean, snapshot: GlobalWorkSnapshot, unavailable: Boolean): Boolean =
        unavailable && (visible || snapshot.requiresForegroundProtection) &&
            snapshot.canStartNewTask && !snapshot.coreStarting && !snapshot.restarting
}

/** Shared by automatic supervision and the explicit, foreground-only retry. */
internal suspend fun ensureForegroundProtection(context: Context, vm: CodexViewModel) {
    if (!vm.globalWorkSnapshot().requiresForegroundProtection) {
        vm.errors.value = vm.errors.value - "foregroundProtection"
        return
    }
    try {
        CodexForegroundService.startAndAwaitReady(context)
        vm.errors.value = vm.errors.value - "foregroundProtection"
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException &&
            failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
        vm.errors.value = vm.errors.value + ("foregroundProtection" to
            "系统未允许后台保护，请保持应用在前台：" + (failure.message ?: "启动超时"))
    }
}
