package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadActivityPolicyTest {
    private fun status(type: String, vararg flags: String) =
        JSONObject().put("type", type).put("activeFlags", JSONArray(flags.toList()))
    private fun thread(status: JSONObject) = JSONObject().put("id", "A").put("status", status)

    @Test fun serverReportedThreadStatesAreDistinct() {
        assertEquals(ThreadActivity.Idle, ThreadActivityPolicy.parse(status("notLoaded")))
        assertEquals(ThreadActivity.Idle, ThreadActivityPolicy.parse(status("idle")))
        assertEquals(ThreadActivity.Failed, ThreadActivityPolicy.parse(status("systemError")))
        assertEquals(ThreadActivity.Running, ThreadActivityPolicy.parse(status("active")))
        assertEquals(ThreadActivity.WaitingApproval,
            ThreadActivityPolicy.parse(status("active", "waitingOnApproval")))
        assertEquals(ThreadActivity.WaitingAnswer,
            ThreadActivityPolicy.parse(status("active", "waitingOnUserInput")))
        assertEquals(ThreadActivity.WaitingApproval,
            ThreadActivityPolicy.parse(status("active", "waitingOnUserInput", "waitingOnApproval")))
    }

    @Test fun newerNotificationWinsOverStaleListAndLocalLiveSessionStillWins() {
        val stale = thread(status("active"))
        assertEquals(ThreadActivity.Idle, ThreadActivityPolicy.effective(
            stale, ThreadActivity.Idle, false, 0))
        assertEquals(ThreadActivity.WaitingApproval, ThreadActivityPolicy.effective(
            stale, ThreadActivity.Idle, false, 2))
        assertEquals(ThreadActivity.Running, ThreadActivityPolicy.effective(
            stale, ThreadActivity.Idle, true, 0))
        assertEquals(ThreadActivity.WaitingAnswer, ThreadActivityPolicy.effective(
            stale, ThreadActivity.WaitingAnswer, false, 0))
        assertEquals(ThreadActivity.Running, ThreadActivityPolicy.fromThread(stale))
    }

    @Test fun ephemeralUpdatesAreBoundedAndIdleTransitionOverridesOldActiveState() {
        val status = (1..300).fold(emptyMap<String, ThreadActivity>()) { current, n ->
            ThreadActivityPolicy.updated(current, "thread-$n", ThreadActivity.Running)
        }
        assertEquals(256, status.size)
        assertFalse(status.containsKey("thread-1"))
        assertEquals(ThreadActivity.Running, status["thread-300"])
        val idle = ThreadActivityPolicy.updated(status, "thread-300", ThreadActivity.Idle)
        assertEquals(ThreadActivity.Idle, idle["thread-300"])
        assertFalse(ThreadActivityPolicy.isActive(ThreadActivity.Failed))
        assertTrue(ThreadActivityPolicy.isActive(ThreadActivity.WaitingAnswer))
    }
}
