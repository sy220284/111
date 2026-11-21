package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ThreadDisplayPolicyTest {
    @Test fun epochSecondsRenderAsReadableLocalDate() {
        assertEquals("2023-11-15 06:13",
            ThreadDisplayPolicy.updatedAt(1_700_000_000L, null, ZoneId.of("Asia/Singapore")))
    }

    @Test fun missingUpdatedAtUsesCreatedAt() {
        assertEquals("2023-11-14 22:13",
            ThreadDisplayPolicy.updatedAt(null, 1_700_000_000L, ZoneId.of("UTC")))
    }

    @Test fun nullOrBrokenTimestampsHaveNoFakeDate() {
        assertNull(ThreadDisplayPolicy.updatedAt("", 0, ZoneId.of("UTC")))
        assertNull(ThreadDisplayPolicy.updatedAt("invalid", null, ZoneId.of("UTC")))
    }

    @Test fun aRunningTaskCannotSwitchToAnotherHistoryThread() {
        assertFalse(ThreadDisplayPolicy.canOpenThread(true, "running", "another"))
        assertTrue(ThreadDisplayPolicy.canOpenThread(true, "running", "running"))
        assertTrue(ThreadDisplayPolicy.canOpenThread(false, "running", "another"))
        assertFalse(ThreadDisplayPolicy.canOpenThread(false, "running", ""))
    }

    @Test fun onlySuccessfulSubmissionClearsMatchingDraft() {
        assertEquals("", ThreadDisplayPolicy.afterAcceptedDraft("do task", "do task"))
        assertEquals("new text", ThreadDisplayPolicy.afterAcceptedDraft("new text", "do task"))
    }
}
