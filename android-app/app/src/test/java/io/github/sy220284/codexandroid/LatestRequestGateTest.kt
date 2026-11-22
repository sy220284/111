package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class LatestRequestGateTest {
    @Test fun olderRequestCannotOverrideOrDismissNewRequest() {
        val gate = LatestRequestGate()
        val previous = gate.begin("review")
        val current = gate.begin("review")
        assertFalse(gate.isLatest("review", previous))
        assertFalse(gate.complete("review", previous))
        assertTrue(gate.isLatest("review", current))
        assertTrue(gate.complete("review", current))
        assertFalse(gate.isLatest("review", previous))
        assertFalse(gate.complete("review", previous))
    }

    @Test fun independentKeysCanCompleteInEitherOrder() {
        val gate = LatestRequestGate()
        val first = gate.begin("review")
        val second = gate.begin("mcpCall:server:tool")
        assertTrue(gate.complete("mcpCall:server:tool", second))
        assertTrue(gate.isLatest("review", first))
        assertTrue(gate.complete("review", first))
    }

    @Test fun evictedSearchRequestCannotOverwriteNewData() {
        val gate = LatestRequestGate()
        val old = gate.begin("library:older-page")
        gate.cancel("library:older-page")
        assertFalse(gate.isLatest("library:older-page", old))
        assertFalse(gate.complete("library:older-page", old))
        val newer = gate.begin("library:older-page")
        assertTrue(gate.isLatest("library:older-page", newer))
        assertFalse(gate.isLatest("library:older-page", old))
    }

    @Test fun nextRequestAfterCompletionGetsFreshOwnership() {
        val gate = LatestRequestGate()
        val previous = gate.begin("pluginSkill:one")
        assertTrue(gate.complete("pluginSkill:one", previous))
        val next = gate.begin("pluginSkill:one")
        assertFalse(gate.isLatest("pluginSkill:one", previous))
        assertTrue(gate.isLatest("pluginSkill:one", next))
    }
}
