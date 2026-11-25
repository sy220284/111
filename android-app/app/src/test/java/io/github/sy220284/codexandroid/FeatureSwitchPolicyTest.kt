package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeatureSwitchPolicyTest {
    private fun row(name: String, stage: String = "stable", enabled: Boolean = false) =
        FeatureSwitchPolicy.parse(JSONObject().put("name", name).put("stage", stage)
            .put("enabled", enabled).put("defaultEnabled", false)
            .put("displayName", JSONObject.NULL).put("description", JSONObject.NULL))

    @Test fun allLiveStagesAndFutureNamesRemainVisible() {
        assertEquals("future flag", row("future_flag", "underDevelopment").title)
        assertNull(row("future_flag", "underDevelopment").restriction(emptySet()))
        assertNotNull(row("future_flag", "unknown").restriction(emptySet()))
        assertNotNull(row("old_flag", "removed").restriction(emptySet()))
        assertEquals("guardianv2.thread_context", row("guardianv2.thread_context", "removed").name)
        assertNotNull(row("guardianv2.thread_context", "removed").restriction(emptySet()))
        assertNotNull(row("future.dotted", "stable").restriction(emptySet()))
        assertNotNull(row("memories").restriction(setOf("memories")))
        assertNotNull(row("respect_system_proxy", "beta").restriction(emptySet()))
        assertEquals("长期记忆", row("memories").title)
    }

    @Test fun catalogGroupingNeverDropsUnknownOrRetiredFeatures() {
        val rows = listOf(row("memories"), row("guardian_approval"), row("code_mode"),
            row("recommended_plugins"), row("reasoning_effort_override"), row("in_app_voice"),
            row("future_flag", "unknown"), row("old_flag", "removed"))
        assertEquals(rows.size, rows.groupBy { FeatureSwitchPolicy.group(it.name) }.values.sumOf { it.size })
        assertTrue(rows.all { FeatureSwitchPolicy.matches(it, "all") })
        assertEquals(FeatureSwitchPolicy.UseGroup.Advanced, FeatureSwitchPolicy.group("future_flag"))
        assertTrue(FeatureSwitchPolicy.matches(rows.last(), "retired"))
        assertTrue(FeatureSwitchPolicy.matches(row("code_mode"), "preferred"))
        assertTrue(FeatureSwitchPolicy.matches(row("memories", enabled = true), "enabled"))
        assertFalse(FeatureSwitchPolicy.matches(row("memories"), "enabled"))
        assertFalse(FeatureSwitchPolicy.needsConfirmation(row("suppress_model_change_announcements")))
        assertTrue(FeatureSwitchPolicy.needsConfirmation(row("suppress_model_change_announcements", "beta")))
        assertTrue(FeatureSwitchPolicy.needsConfirmation(row("memories")))
        assertTrue(FeatureSwitchPolicy.needsConfirmation(row("future_flag")))
    }

    @Test fun featureWritesOnlyOneBooleanAndReloadsUserConfiguration() {
        val params = FeatureSwitchPolicy.writeParams("memories", false, "version")
        assertEquals("version", params.getString("expectedVersion"))
        assertTrue(params.getBoolean("reloadUserConfig"))
        val edits = params.getJSONArray("edits")
        assertEquals(1, edits.length())
        val edit = edits.getJSONObject(0)
        assertEquals("features.memories", edit.getString("keyPath"))
        assertFalse(edit.getBoolean("value"))
        assertEquals("replace", edit.getString("mergeStrategy"))
        assertThrows(IllegalArgumentException::class.java) {
            FeatureSwitchPolicy.writeParams("x.other", true, null)
        }
    }
}
