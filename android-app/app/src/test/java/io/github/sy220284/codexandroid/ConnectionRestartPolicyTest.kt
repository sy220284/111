package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ConnectionRestartPolicyTest {
    private fun idle() = GlobalWorkSnapshot(false, 0, false, false, 0, false)

    @Test fun refusesToDestroyTheCoreDuringVisibleTaskTerminalOrApproval() {
        assertFalse(ConnectionRestartPolicy.canRestart(idle().copy(visibleTurnRunning = true)))
        assertFalse(ConnectionRestartPolicy.canRestart(idle().copy(terminalRunning = true)))
        assertFalse(ConnectionRestartPolicy.canRestart(idle().copy(pendingApprovals = 2)))
        assertTrue(ConnectionRestartPolicy.canRestart(idle()))
    }

    @Test fun ownedRestartLeaseDoesNotBlockItsOwnSecondSafetyCheck() {
        val owned = idle().copy(restarting = true)
        assertFalse(ConnectionRestartPolicy.canRestart(owned))
        assertTrue(ConnectionRestartPolicy.canContinueOwnedRestart(owned))
        assertFalse(ConnectionRestartPolicy.canContinueOwnedRestart(
            owned.copy(executingThreads = 1)))
        assertFalse(ConnectionRestartPolicy.canContinueOwnedRestart(
            owned.copy(inFlightRequests = 1)))
        assertFalse(ConnectionRestartPolicy.canContinueOwnedRestart(
            owned.copy(dataTransferBusy = true)))
    }

    @Test fun backgroundSessionsAndFirstTurnAlsoBlockDestructiveRestart() {
        assertFalse(ConnectionRestartPolicy.canRestart(idle().copy(executingThreads = 1)))
        assertFalse(ConnectionRestartPolicy.canRestart(idle().copy(creatingThread = true)))
        assertFalse(ConnectionRestartPolicy.canRestart(idle().copy(dataTransferBusy = true)))
        assertTrue(ConnectionRestartPolicy.canRestart(idle().copy(executingThreads = 0)))
    }
}
