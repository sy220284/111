package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadGoalRequestContractTest {
    private fun build(budget: String, edit: Boolean, existing: Boolean) =
        ThreadGoalRequestContract.set("thread-1", "完成审查并修复问题", "active",
            budget, edit, existing)

    @Test fun unconfirmedBudgetEditPreservesExistingLimit() {
        val payload = build("", edit = false, existing = true)
        assertFalse(payload.has("tokenBudget"))
        assertEquals("thread-1", payload.getString("threadId"))
        assertEquals("user", payload.getString("origin"))
    }

    @Test fun confirmedBudgetEditCanSetPositiveLimit() {
        val payload = build("12000", edit = true, existing = true)
        assertEquals(12000L, payload.getLong("tokenBudget"))
    }

    @Test fun confirmedEmptyBudgetExplicitlyClearsExistingLimit() {
        val payload = build("", edit = true, existing = true)
        assertTrue(payload.has("tokenBudget"))
        assertTrue(payload.isNull("tokenBudget"))
    }

    @Test fun noExistingBudgetDoesNotRequireAnUnnecessaryNullWrite() {
        assertFalse(build("", edit = true, existing = false).has("tokenBudget"))
    }

    @Test fun overflowsAndZeroAreRejectedBeforeRpc() {
        for (input in listOf("0", "999999999999999999999999", "-1", "x")) {
            try {
                build(input, edit = true, existing = false)
                fail("invalid tokenBudget accepted: $input")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test fun invalidThreadAndStatusAreRejected() {
        try {
            ThreadGoalRequestContract.set("", "任务", "active", "", false, false)
            fail("empty thread was accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            ThreadGoalRequestContract.set("thread-1", "任务", "unknown", "", false, false)
            fail("unknown status was accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun staleDraftBudgetIsIgnoredWithoutConfirmedEditIntent() {
        val payload = build("12000", edit = false, existing = true)
        assertFalse(payload.has("tokenBudget"))
    }
}
