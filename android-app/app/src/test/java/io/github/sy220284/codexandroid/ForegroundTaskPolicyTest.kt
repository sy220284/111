package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ForegroundTaskPolicyTest {
    @Test fun pendingApprovalKeepsNotificationEvenWhenCoreDisconnected() {
        assertTrue(ForegroundTaskPolicy.shouldKeepAlive(false, false, 1))
        assertTrue(ForegroundTaskPolicy.shouldReconnect(false, false, false, 1))
    }

    @Test fun runningTaskStaysAliveButIdleServiceStops() {
        assertTrue(ForegroundTaskPolicy.shouldKeepAlive(true, false, 0))
        assertFalse(ForegroundTaskPolicy.shouldReconnect(true, true, false, 0))
        assertFalse(ForegroundTaskPolicy.shouldKeepAlive(false, false, 0))
        assertFalse(ForegroundTaskPolicy.shouldReconnect(false, false, false, 0))
    }

    @Test fun terminalAloneStaysProtectedAndCanRecoverConnection() {
        assertTrue(ForegroundTaskPolicy.shouldKeepAlive(false, true, 0))
        assertTrue(ForegroundTaskPolicy.shouldReconnect(false, false, true, 0))
        assertFalse(ForegroundTaskPolicy.shouldReconnect(true, false, true, 0))
    }

    @Test fun offscreenTurnCanSustainProtectionWithoutVisibleConversation() {
        assertTrue(ForegroundTaskPolicy.shouldKeepAlive(true, false, 0))
        assertTrue(ForegroundTaskPolicy.shouldReconnect(false, true, false, 0))
    }
}
