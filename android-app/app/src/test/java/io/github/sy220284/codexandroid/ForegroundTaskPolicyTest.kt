package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ForegroundTaskPolicyTest {
    @Test fun pendingApprovalKeepsNotificationEvenWhenCoreDisconnected() {
        assertTrue(ForegroundTaskPolicy.shouldKeepAlive(false, 1))
        assertTrue(ForegroundTaskPolicy.shouldReconnect(false, false, 1))
    }

    @Test fun runningTaskStaysAliveButIdleServiceStops() {
        assertTrue(ForegroundTaskPolicy.shouldKeepAlive(true, 0))
        assertFalse(ForegroundTaskPolicy.shouldReconnect(true, true, 0))
        assertFalse(ForegroundTaskPolicy.shouldKeepAlive(false, 0))
        assertFalse(ForegroundTaskPolicy.shouldReconnect(false, false, 0))
    }
}
