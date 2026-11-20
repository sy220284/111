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
    @Test fun steeringUsesExpectedRunningTurnAndTypedUserInput() {
        val request = TurnRequestContract.steer("thread-1", "turn-2", "  先检查页面回归  ")
        assertEquals("thread-1", request.getString("threadId"))
        assertEquals("turn-2", request.getString("expectedTurnId"))
        val input = request.getJSONArray("input").getJSONObject(0)
        assertEquals("text", input.getString("type"))
        assertEquals("先检查页面回归", input.getString("text"))
    }

    @Test fun steeringRejectsEmptyInstructionOrMissingRunningTurn() {
        for (params in listOf(
            Triple("", "turn-2", "补充指令"),
            Triple("thread-1", "", "补充指令"),
            Triple("thread-1", "turn-2", "   ")
        )) {
            try {
                TurnRequestContract.steer(params.first, params.second, params.third)
                fail("Invalid steer request should be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun textWithLocalImagesBuildsOfficialTypedInputOrder() {
        val request = TurnRequestContract.build(
            threadId = "t-images",
            text = "分析两个界面的差异",
            modelId = "",
            mode = "default",
            catalog = models,
            localImages = listOf("/data/user/0/cache/codex-turn-images/one.png",
                "/data/user/0/cache/codex-turn-images/two.jpg")
        )
        val input = request.getJSONArray("input")
        assertEquals(3, input.length())
        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertEquals("localImage", input.getJSONObject(1).getString("type"))
        assertEquals("/data/user/0/cache/codex-turn-images/two.jpg",
            input.getJSONObject(2).getString("path"))
    }

    @Test fun localImagesCanBeTheEntirePromptButMustBeValid() {
        val input = TurnRequestContract.build("t-image-only", "", "", "default",
            models, listOf("/data/user/0/cache/codex-turn-images/one.png"))
            .getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("localImage", input.getJSONObject(0).getString("type"))
        for (paths in listOf(
            emptyList(),
            listOf("relative.png"),
            listOf("/one.png", "/two.png", "/three.png", "/four.png")
        )) {
            try {
                TurnRequestContract.build("t", "", "", "default", models, paths)
                fail("Invalid attachment request should be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

}
