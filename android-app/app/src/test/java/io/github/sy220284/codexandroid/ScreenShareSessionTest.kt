package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ScreenShareSessionTest {
    @Test fun notificationAndStopAnalysisFollowTheConsentedCaptureWithoutStoppingLocalSharing() {
        val session = ScreenShareSession()
        val token = checkNotNull(session.reserve("thread-a", 3))
        assertFalse(session.analyzing(token, true))
        assertTrue(session.started(token))
        assertTrue(session.state.value.notificationText.contains("仅在本机"))
        assertTrue(session.analyzing(token, true))
        assertTrue(session.state.value.notificationText.contains("百炼"))
        assertTrue(session.state.value.notificationText.contains("费用"))
        session.publish(token, byteArrayOf(1))
        assertTrue(session.analyzing(token, false))
        assertTrue(session.state.value.capturing)
        assertNotNull(session.snapshot("thread-a", 3))
        assertTrue(session.state.value.notificationText.contains("仅在本机"))
        session.analyzing(token, true)
        session.stopping(token)
        assertFalse(session.state.value.analyzing)
        assertFalse(session.analyzing(token, true))
        session.finish(token)
        val next = checkNotNull(session.reserve("thread-b", 4))
        session.started(next)
        session.analyzing(next, true)
        assertFalse(session.analyzing(token, false))
        assertTrue(session.state.value.analyzing)
    }

    @Test fun framesBelongOnlyToTheConsentedThreadAndAccountAndAreClearedOnStop() {
        val session = ScreenShareSession()
        val token = checkNotNull(session.reserve("thread-a", 3))
        assertNull(session.reserve("thread-b", 3))
        session.publish(token, byteArrayOf(9))
        assertNull(session.snapshot("thread-a", 3))
        assertTrue(session.started(token))
        assertFalse(session.started(token))
        session.publish(token, byteArrayOf(1, 2, 3))
        assertNull(session.snapshot("thread-b", 3))
        assertNull(session.snapshot("thread-a", 4))
        val snapshot = checkNotNull(session.snapshot("thread-a", 3))
        snapshot.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), session.snapshot("thread-a", 3))
        session.invalidate(token)
        assertNull(session.snapshot("thread-a", 3))
        session.publish(token, byteArrayOf(7))
        session.finish(token)
        assertNull(session.snapshot("thread-a", 3))
        val next = checkNotNull(session.reserve("thread-b", 3))
        assertTrue(session.started(next))
        session.publish(token, byteArrayOf(8))
        assertNull(session.snapshot("thread-b", 3))
    }
}

