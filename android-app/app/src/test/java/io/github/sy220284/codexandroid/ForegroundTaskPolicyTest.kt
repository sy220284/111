package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ForegroundTaskPolicyTest {
    private fun idle() = GlobalWorkSnapshot(false, 0, false, false, 0, false)

    @Test fun allUserOwnedTasksRetainProtectionButPassiveReadsDoNot() {
        val busy = listOf(idle().copy(pendingApprovals = 1), idle().copy(executingThreads = 1),
            idle().copy(terminalRunning = true), idle().copy(terminalStarting = true),
            idle().copy(realtimeVoiceActive = true), idle().copy(inFlightUserRequests = 1),
            idle().copy(loginBusy = true), idle().copy(securitySaveBusy = true),
            idle().copy(dataTransferBusy = true))
        busy.forEach { assertTrue(ForegroundTaskPolicy.shouldKeepAlive(it)) }
        assertFalse(ForegroundTaskPolicy.shouldKeepAlive(idle()))
        assertFalse(ForegroundTaskPolicy.shouldKeepAlive(idle().copy(inFlightRequests = 1)))
        assertFalse(idle().copy(inFlightRequests = 1).canDisruptCoreOrData)
    }

    @Test fun recoveryRequiresDemandAndNeverOverlapsRetirementOrMaintenance() {
        assertTrue(RuntimeRecoveryPolicy.shouldRecover(true, idle(), true))
        assertTrue(RuntimeRecoveryPolicy.shouldRecover(false, idle().copy(executingThreads = 1), true))
        assertFalse(RuntimeRecoveryPolicy.shouldRecover(false, idle(), true))
        assertFalse(RuntimeRecoveryPolicy.shouldRecover(true, idle(), false))
        assertFalse(RuntimeRecoveryPolicy.shouldRecover(true, idle().copy(coreStarting = true), true))
        assertFalse(RuntimeRecoveryPolicy.shouldRecover(true, idle().copy(restarting = true), true))
        assertFalse(RuntimeRecoveryPolicy.shouldRecover(true, idle().copy(dataTransferBusy = true), true))
    }
}
