package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RealtimeVoiceLeaseTest {
    @Test fun failedStopRemainsActiveAndRetryableAcrossControllers() {
        val lease = RealtimeVoiceLease()
        val old = Any()
        val next = Any()
        var retries = 0
        assertFalse(lease.active.value)
        assertTrue(lease.acquire(old))
        lease.markFailed(old, "未收到关闭回执") { retries++ }
        assertTrue(lease.active.value)
        assertEquals("未收到关闭回执", lease.stopFailure.value)
        assertFalse(lease.acquire(next))
        lease.release(next)
        assertTrue(lease.active.value)
        lease.retryFailedStop()
        assertEquals(1, retries)
        lease.clearFailure(old)
        assertNull(lease.stopFailure.value)
        assertTrue(lease.active.value)
        lease.retryFailedStop()
        assertEquals(1, retries)
        lease.release(old)
        assertFalse(lease.active.value)
        assertTrue(lease.acquire(next))
    }

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
