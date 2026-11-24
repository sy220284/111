package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineSettingsTest {
    @Test fun numericLimitsAndNativeTypes() {
        val percentage = EngineSettings.byKey("model_post_turn_compact_threshold_percent")!!
        assertEquals(100L, percentage.parse("100"))
        assertEquals(0L, percentage.parse("0"))
        assertTrue(runCatching { percentage.parse("101") }.isFailure)
        assertTrue(runCatching { percentage.parse("1.5") }.isFailure)
        val context = EngineSettings.byKey("model_context_window")!!
        assertTrue(runCatching { context.parse("0") }.isFailure)
        assertEquals(true, EngineSettings.byKey("hide_agent_reasoning")!!.parse("true"))
        assertEquals("由引擎决定", context.label(null))
        assertEquals(128000L, context.parse("128000"))
    }

    @Test fun choiceUsesActualModelAndRequirements() {
        val models = listOf(JSONObject().put("id", "m1").put("isDefault", true)
            .put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "high"))))
        assertEquals(setOf("high"), EngineSettings.reasoningChoices(JSONObject(), models).keys)
        assertTrue(EngineSettings.reasoningChoices(JSONObject().put("model", "other"), models).isEmpty())
        val web = EngineSettings.byKey("web_search")!!
        val requirements = JSONObject().put("allowedWebSearchModes", JSONArray().put("cached"))
        assertEquals(setOf("cached"), EngineSettings.choices(web, null, models, requirements, true).keys)
        assertTrue(EngineSettings.choices(web, null, models, null, false).isEmpty())
        assertTrue(runCatching { web.parse("live", mapOf("cached" to "缓存搜索")) }.isFailure)
    }

    @Test fun readbackMustMatchAndNotBeOverridden() {
        val write = JSONObject().put("status", "ok")
        val read = JSONObject().put("config", JSONObject().put("tool_output_token_limit", 20000))
        EngineSettings.verify(write, read, "tool_output_token_limit", 20000L)
        assertTrue(runCatching { EngineSettings.verify(write, read, "tool_output_token_limit", 100L) }.isFailure)
        assertTrue(runCatching { EngineSettings.verify(JSONObject().put("status", "okOverridden"),
            read, "tool_output_token_limit", 20000L) }.isFailure)
        val params = EngineSettings.writeParams("tool_output_token_limit", 20000L)
        assertEquals("replace", params.getString("mergeStrategy"))
        assertTrue(params.get("value") is Number)
        assertTrue(EngineSettings.sameValue(JSONObject().put("x", 1), JSONObject().put("x", 1L)))
        assertFalse(EngineSettings.sameValue(JSONObject().put("x", true), JSONObject().put("x", "true")))
    }
}
