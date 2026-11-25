package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ScreenShareSequenceTest {
    @Test fun changedFramesHaveBoundedOrderedSnapshotsAndNoCrossSessionLeakage() {
        val session = ScreenShareSession()
        val token = checkNotNull(session.reserve("owner", 2))
        assertTrue(session.started(token))
        for (i in 1..9) session.publish(token, byteArrayOf(i.toByte()), i * 1000L)
        session.publish(token, byteArrayOf(9), 10_000)
        assertEquals(9L, session.state.value.sequence)
        val frames = session.framesAfter(token, "owner", 2, 0)
        assertEquals(listOf(4L, 7L, 9L), frames.map { it.sequence })
        assertEquals(listOf(3000L, 6000L, 8000L), frames.map { it.timeMs })
        assertTrue(session.framesAfter(token, "other", 2, 0).isEmpty())
        assertTrue(session.framesAfter(token, "owner", 3, 0).isEmpty())
        assertTrue(session.framesAfter(token, "owner", 2, 9).isEmpty())
        session.invalidate(token)
        assertTrue(session.framesAfter(token, "owner", 2, 0).isEmpty())
        session.publish(token, byteArrayOf(10), 11_000)
        assertEquals(10L, session.state.value.sequence)
        session.stopping(token)
        assertNull(session.snapshot("owner", 2))
        assertNull(session.reserve("other", 2))
        session.finish(token)
        assertNotNull(session.reserve("other", 2))
    }
}
