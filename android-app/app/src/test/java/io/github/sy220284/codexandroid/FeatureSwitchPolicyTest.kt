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
        assertTrue(row("future_flag", "underDevelopment").matches("future_flag"))
        assertNull(row("future_flag", "underDevelopment").restriction(emptySet()))
        assertNotNull(row("future_flag", "unknown").restriction(emptySet()))
        assertNotNull(row("old_flag", "removed").restriction(emptySet()))
        assertEquals("guardianv2.thread_context", row("guardianv2.thread_context", "removed").name)
        assertNotNull(row("guardianv2.thread_context", "removed").restriction(emptySet()))
        assertNotNull(row("future.dotted", "stable").restriction(emptySet()))
        assertNotNull(row("memories").restriction(setOf("memories")))
        assertNotNull(row("respect_system_proxy", "beta").restriction(emptySet()))
        assertTrue(row("memories").matches("长期记忆"))
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
