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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            combine(foreground, work, vm.bridge.status) { visible, snapshot, status ->
                RuntimeRecoveryPolicy.shouldRecover(visible, snapshot, status is EngineStatus.Unavailable)
            }.distinctUntilChanged().collectLatest { recover ->
                if (!recover) return@collectLatest
                var interval = 1_000L
                while (RuntimeRecoveryPolicy.shouldRecover(foreground.value, vm.globalWorkSnapshot(),
                        vm.bridge.status.value is EngineStatus.Unavailable)) {
                    delay(interval)
                    if (!RuntimeRecoveryPolicy.shouldRecover(foreground.value, vm.globalWorkSnapshot(),
                            vm.bridge.status.value is EngineStatus.Unavailable)) break
                    vm.connect().join()
                    interval = (interval * 2).coerceAtMost(60_000L)
                }
            }
        }
        scope.launch {
            combine(foreground, work) { visible, snapshot ->
                visible && snapshot.requiresForegroundProtection
            }.distinctUntilChanged().collectLatest { protect ->
                if (!protect) return@collectLatest
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
        }
    }
}

internal object RuntimeRecoveryPolicy {
    fun shouldRecover(visible: Boolean, snapshot: GlobalWorkSnapshot, unavailable: Boolean): Boolean =
        unavailable && (visible || snapshot.requiresForegroundProtection) &&
            !snapshot.dataTransferBusy && !snapshot.coreStarting && !snapshot.restarting
}
