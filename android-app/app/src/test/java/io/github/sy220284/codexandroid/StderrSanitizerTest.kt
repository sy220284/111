package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class StderrSanitizerTest {
    @Test fun hidesBearerAndTokenFieldsWithoutRemovingDiagnosticContext() {
        val output = StderrSanitizer.redact(
            "failed Authorization: Bearer abcdefghijklmnop api_key=ABCDEFGHIJKLMN https://local/?token=privatevalue")
        assertTrue(output.contains("failed"))
        assertFalse(output.contains("abcdefghijklmnop"))
        assertFalse(output.contains("ABCDEFGHIJKLMN"))
        assertFalse(output.contains("privatevalue"))
    }

    @Test fun preservesHarmlessStderr() {
        assertEquals("Codex process not found", StderrSanitizer.redact("Codex process not found"))
    }
}
