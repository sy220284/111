package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class OperationOwnershipTest {
    @Test fun newerNotificationInvalidatesInFlightStatusRead() {
        val order = StatusResponseOrder()
        val old = order.begin()
        order.invalidate()
        assertFalse(order.accepts(old, true))
        val current = order.begin()
        assertTrue(order.accepts(current, true))
        assertFalse(order.accepts(current, false))
        order.invalidate()
        assertFalse(order.accepts(current, true))
    }

    @Test fun newerRequestCannotBeOverwrittenByOlderResponse() {
        val order = StatusResponseOrder()
        val first = order.begin()
        val second = order.begin()
        assertFalse(order.accepts(first, true))
        assertTrue(order.accepts(second, true))
    }

    @Test fun queuedAudioBeforeMuteStaysRejectedAfterUnmute() {
        val gate = VoiceUploadGate()
        val before = gate.revision
        assertTrue(gate.accepts(before))
        gate.setMuted(true)
        assertFalse(gate.accepts(before))
        assertFalse(gate.accepts(gate.revision))
        gate.setMuted(false)
        assertFalse(gate.accepts(before))
        assertTrue(gate.accepts(gate.revision))
    }

    @Test fun retriesCannotSendRemainingVersionsToAnotherSection() {
        val move = SectionMoveAttempt("group-a", "甲")
        assertTrue(move.accepts("group-a"))
        assertFalse(move.accepts("group-b"))
        assertFalse(move.accepts(null))
        val ungroup = SectionMoveAttempt(null, "未分组")
        assertTrue(ungroup.accepts(null))
        assertFalse(ungroup.accepts("group-a"))
    }
}
