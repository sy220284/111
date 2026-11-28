package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class PromptSubmissionGateTest {
    @Test fun oneWriterOwnsEachNativePromptUntilPhysicalCompletion() {
        val gate = PromptSubmissionGate()
        assertTrue(gate.claim(1L, "approval"))
        assertFalse(gate.claim(1L, "approval"))
        assertTrue(gate.claim(2L, "approval"))
        assertTrue(gate.claim(1L, "different"))
        gate.release(1L, "approval")
        assertTrue(gate.claim(1L, "approval"))
        assertFalse(gate.claim(2L, "approval"))
    }
}
