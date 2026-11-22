package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class LocalCrashReporterTest {
    @Test fun journalDoesNotRecordPotentiallySensitiveExceptionMessage() {
        val throwable = IllegalStateException("token=secret-user-data-abc123")
        val report = LocalCrashReporter.format(throwable, 1795330000000L)
        assertTrue(report.contains("IllegalStateException"))
        assertFalse(report.contains("secret-user-data-abc123"))
    }
}
