package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class GlobalWorkSnapshotTest {
    private fun idle() = GlobalWorkSnapshot(false, 0, false, false, 0, false)

    @Test fun everyLiveOperationPreventsDataReplacementAndInstallerHandoff() {
        val states = listOf(
            idle().copy(visibleTurnRunning = true),
            idle().copy(executingThreads = 2),
            idle().copy(creatingThread = true),
            idle().copy(terminalRunning = true),
            idle().copy(pendingApprovals = 1),
            idle().copy(dataTransferBusy = true)
        )
        states.forEach { current ->
            assertFalse(current.canDisruptCoreOrData)
            assertTrue(current.blockReason.isNotBlank())
        }
        assertTrue(idle().canDisruptCoreOrData)
        assertEquals("", idle().blockReason)
    }

    @Test fun pendingRpcAuthorizationAndPlanPreparationMustFinishBeforeBackup() {
        val pending = listOf(
            idle().copy(inFlightRequests = 2),
            idle().copy(loginBusy = true),
            idle().copy(planApprovalBusy = true)
        )
        pending.forEach {
            assertTrue(it.hasActiveWork)
            assertFalse(it.canDisruptCoreOrData)
            assertEquals(
                "请求或授权操作正在处理，请完成后再备份、恢复或重启",
                it.blockReason
            )
        }
    }

    @Test fun unrelatedBackgroundTasksDoNotBlockNormalParallelThreadCreation() {
        assertTrue(idle().copy(executingThreads = 3).canStartNewTask)
        assertTrue(idle().copy(terminalRunning = true).canStartNewTask)
        assertFalse(idle().copy(dataTransferBusy = true).canStartNewTask)
    }

    @Test fun visibilityOfCurrentConversationCannotHideRunningBackgroundSessions() {
        val background = idle().copy(visibleTurnRunning = false, executingThreads = 1)
        assertTrue(background.hasActiveWork)
        assertFalse(background.canDisruptCoreOrData)
    }
}
