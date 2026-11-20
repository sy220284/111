package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnRequestContractTest {
    private val models = listOf(
        JSONObject().put("id", "gpt-6").put("isDefault", true)
            .put("defaultReasoningEffort", "high"),
        JSONObject().put("id", "gpt-5.6").put("isDefault", false)
            .put("defaultReasoningEffort", "medium")
    )

    @Test fun ordinaryTurnInheritsModelWithoutForcingCollaborationSettings() {
        val request = TurnRequestContract.build("t1", "修复登录功能", "", "default", models)
        assertEquals("t1", request.getString("threadId"))
        assertEquals("修复登录功能",
            request.getJSONArray("input").getJSONObject(0).getString("text"))
        assertFalse(request.has("collaborationMode"))
        assertFalse(request.has("model"))
    }

    @Test fun explicitModelOverrideUsesNativeTurnField() {
        val request = TurnRequestContract.build("t2", "检查改动", "gpt-5.6", "default", models)
        assertEquals("gpt-5.6", request.getString("model"))
        assertFalse(request.has("collaborationMode"))
    }

    @Test fun planModeUsesRustSettingsFieldNamesExactly() {
        val request = TurnRequestContract.build("t3", "制定完整计划", "", "plan", models)
        val mode = request.getJSONObject("collaborationMode")
        assertEquals("plan", mode.getString("mode"))
        val settings = mode.getJSONObject("settings")
        assertEquals("gpt-6", settings.getString("model"))
        assertEquals("high", settings.getString("reasoning_effort"))
        assertTrue(settings.isNull("developer_instructions"))
        assertFalse(settings.has("reasoningEffort"))
        assertFalse(settings.has("developerInstructions"))
    }

    @Test fun planModeRespectsExplicitModelAndRequiresOne() {
        val request = TurnRequestContract.build("t4", "计划", "gpt-5.6", "plan", models)
        assertEquals("gpt-5.6",
            request.getJSONObject("collaborationMode").getJSONObject("settings").getString("model"))
        try {
            TurnRequestContract.build("t4", "计划", "", "plan", emptyList())
            fail("Plan mode must not silently choose an unavailable model")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun unsupportedModeFailsBeforeSubmitting() {
        try {
            TurnRequestContract.build("t5", "执行", "", "fullyAutonomous", models)
            fail("Unknown mode must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
}
