package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class CoreSessionOwnershipTest {
    @Test fun handshakeDoesNotAdmitBusinessRequestsAndRestartReservesAdmission() {
        val owner = CoreSessionOwnership()
        val epoch = owner.begin()
        assertFalse(owner.acceptsUserRequest())
        assertFalse(owner.reserveRestart(0, 0))
        assertTrue(owner.markReady(epoch))
        assertTrue(owner.acceptsUserRequest())
        assertFalse(owner.reserveRestart(1, 0))
        assertFalse(owner.reserveRestart(0, 1))
        assertTrue(owner.reserveRestart(0, 0))
        assertFalse(owner.acceptsUserRequest())
        assertTrue(owner.owns(epoch))
        owner.releaseRestart()
        assertTrue(owner.acceptsUserRequest())
    }

    @Test fun oldReaderCannotRetireOrMarkNewSessionReady() {
        val owner = CoreSessionOwnership()
        val old = owner.begin()
        assertTrue(owner.markReady(old))
        assertTrue(owner.reserveRestart(0, 0))
        val retired = requireNotNull(owner.retire(old))
        assertTrue(owner.isRetired(retired))
        val next = owner.begin()
        assertFalse(owner.isRetired(retired))
        assertFalse(owner.owns(old))
        assertFalse(owner.markReady(old))
        assertNull(owner.retire(old))
        assertFalse(owner.acceptsUserRequest())
        assertTrue(owner.markReady(next))
        assertTrue(owner.acceptsUserRequest())
    }

    @Test fun queuedAudioPermitIsRevokedByMuteUnmuteAndStop() {
        val gate = VoiceUploadGate()
        val beforeMute = gate.revision
        assertTrue(gate.accepts(beforeMute))
        gate.setMuted(true)
        gate.setMuted(false)
        assertFalse(gate.accepts(beforeMute))
        val beforeStop = gate.revision
        gate.invalidate()
        assertFalse(gate.accepts(beforeStop))
        assertTrue(gate.accepts(gate.revision))
    }
}
