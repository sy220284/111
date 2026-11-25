package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class GlobalWorkSnapshotTest {
    private fun idle() = GlobalWorkSnapshot(false, 0, false, false, 0, false)

    @Test fun voiceOccupancyBlocksMaintenanceEvenWithoutWireRequests() {
        val voice = idle().copy(realtimeVoiceActive = true)
        assertTrue(voice.hasActiveWork)
        assertFalse(voice.canDisruptCoreOrData)
        assertTrue(voice.canStartNewTask)
        assertTrue(voice.blockReason.contains("实时语音"))
        assertFalse(ConnectionRestartPolicy.canRecoverVoice(voice, false))
        assertTrue(ConnectionRestartPolicy.canRecoverVoice(voice, true))
        assertFalse(ConnectionRestartPolicy.canRecoverVoice(voice.copy(executingThreads = 1), true))
        assertFalse(ConnectionRestartPolicy.canRecoverVoice(voice.copy(dataTransferBusy = true), true))
        assertFalse(ConnectionRestartPolicy.canRecoverVoice(voice.copy(inFlightRequests = 1), true))
    }

    @Test fun everyLiveOperationPreventsDataReplacementAndInstallerHandoff() {
        val states = listOf(
            idle().copy(visibleTurnRunning = true),
            idle().copy(executingThreads = 2),
            idle().copy(creatingThread = true),
            idle().copy(terminalRunning = true),
            idle().copy(pendingApprovals = 1),
            idle().copy(localMutations = 1),
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

    @Test fun directWireRequestsAndSettingsReadbackPreventGlobalDisruption() {
        val states = listOf(
            idle().copy(inFlightRequests = 1),
            idle().copy(switchingThread = true),
            idle().copy(securitySaveBusy = true),
            idle().copy(coreStarting = true),
            idle().copy(restarting = true),
            idle().copy(terminalStarting = true)
        )
        states.forEach { snapshot ->
            assertTrue(snapshot.hasActiveWork)
            assertFalse(snapshot.canDisruptCoreOrData)
            assertTrue(snapshot.blockReason.isNotBlank())
        }
    }

    @Test fun unrelatedRequestsStillAllowSeparateThreadWorkUntilBackupStarts() {
        val reading = idle().copy(inFlightRequests = 3, executingThreads = 1)
        assertFalse(reading.canDisruptCoreOrData)
        assertTrue(reading.canStartNewTask)
        assertFalse(reading.copy(dataTransferBusy = true).canStartNewTask)
    }

    @Test fun visibilityOfCurrentConversationCannotHideRunningBackgroundSessions() {
        val background = idle().copy(visibleTurnRunning = false, executingThreads = 1)
        assertTrue(background.hasActiveWork)
        assertFalse(background.canDisruptCoreOrData)
    }
}
