package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ConnectionRestartPolicyTest {
    @Test fun refusesToDestroyTheCoreDuringAnActiveTaskOrApproval() {
        assertFalse(ConnectionRestartPolicy.canRestart(true, false, 0))
        assertFalse(ConnectionRestartPolicy.canRestart(false, true, 0))
        assertFalse(ConnectionRestartPolicy.canRestart(false, false, 2))
        assertTrue(ConnectionRestartPolicy.canRestart(false, false, 0))
    }
}
