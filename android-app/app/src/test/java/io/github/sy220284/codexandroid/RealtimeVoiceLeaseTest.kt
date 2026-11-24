package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RealtimeVoiceLeaseTest {
    @Test fun recreatedControllerCannotStartUntilOldMediaCleanupReleasesLease() {
        val lease = RealtimeVoiceLease()
        val old = Any()
        val next = Any()
        assertTrue(lease.acquire(old))
        assertFalse(lease.acquire(old))
        assertFalse(lease.acquire(next))
        lease.release(next)
        assertFalse(lease.acquire(next))
        lease.release(old)
        assertTrue(lease.acquire(next))
        lease.release(old)
        assertFalse(lease.acquire(old))
        lease.release(next)
        assertTrue(lease.acquire(old))
    }
}
