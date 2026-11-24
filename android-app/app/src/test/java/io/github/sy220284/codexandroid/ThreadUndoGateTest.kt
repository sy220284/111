package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ThreadUndoGateTest {
    @Test fun onlyAcknowledgedIdsAreReversibleIncludingPartialBatch() {
        val gate = ThreadUndoGate()
        val ticket = gate.begin("thread/archive", listOf("a", "b"))!!
        assertNull(gate.complete(ticket, emptyList()))
        val undo = gate.complete(ticket, listOf("a", "not-requested", "a"))!!
        assertEquals("unarchive", undo.kind)
        assertEquals(listOf("a"), undo.ids)
        assertTrue(gate.consume(undo))
        assertFalse(gate.consume(undo))
    }
    @Test fun restoreHasInverseButDeletionAndUnknownActionsDoNot() {
        val gate = ThreadUndoGate()
        val restored = gate.begin("thread/unarchive", listOf("a"))
        val undo = gate.complete(restored, listOf("a"))!!
        assertEquals("archive", undo.kind)
        assertNull(gate.begin("thread/delete", listOf("a")))
        assertFalse(gate.isCurrent(undo))
        assertNull(gate.complete(null, listOf("a")))
        assertNull(gate.begin("thread/name/set", listOf("a")))
    }
    @Test fun staleOperationsAndOppositeServerEventsInvalidateUndo() {
        val gate = ThreadUndoGate()
        val old = gate.begin("thread/archive", listOf("a"))
        val fresh = gate.begin("thread/archive", listOf("b"))
        assertNull(gate.complete(old, listOf("a")))
        gate.observe("thread/archived", "b")
        val undo = gate.complete(fresh, listOf("b"))!!
        gate.observe("thread/unarchived", "a")
        assertTrue(gate.isCurrent(undo))
        gate.observe("thread/unarchived", "b")
        assertFalse(gate.consume(undo))
    }
    @Test fun disconnectAndDeletionInvalidateReceiptBeforeItCanRun() {
        val gate = ThreadUndoGate()
        val ticket = gate.begin("thread/archive", listOf("a"))
        val undo = gate.complete(ticket, listOf("a"))!!
        gate.observe("thread/deleted", "a")
        assertFalse(gate.consume(undo))
        val again = gate.begin("thread/archive", listOf("a"))
        gate.invalidate()
        assertNull(gate.complete(again, listOf("a")))
    }
}
