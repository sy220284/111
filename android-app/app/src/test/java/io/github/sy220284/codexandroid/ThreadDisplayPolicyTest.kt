package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.LocalDate

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

    @Test fun sessionDayGroupUsesServerEpochInTheSuppliedZone() {
        val zone = ZoneId.of("Asia/Singapore")
        val today = LocalDate.of(2026, 11, 22)
        val midnight = today.atStartOfDay(zone).toEpochSecond()
        assertEquals("今天", ThreadDisplayPolicy.dayGroup(midnight, null, today, zone))
        assertEquals("昨天", ThreadDisplayPolicy.dayGroup(midnight - 1L, null, today, zone))
        assertEquals("更早", ThreadDisplayPolicy.dayGroup(midnight - 2L * 86400L, null, today, zone))
        assertEquals("日期未知", ThreadDisplayPolicy.dayGroup("not-a-time", null, today, zone))
        assertEquals("今天", ThreadDisplayPolicy.dayGroup(null, midnight, today, zone))
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
