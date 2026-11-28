package io.github.sy220284.codexandroid

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ComposerPermissionPolicyTest {
    @Test fun builtInProfilesOnlyAndServerRestrictionsAreRespected() {
        val profiles = ComposerPermissionPolicy.ids.map { JSONObject().put("id", it).put("allowed", true) }
        val requirements = JSONObject().put("requirements", JSONObject()
            .put("allowedPermissionProfiles", JSONObject().put(":danger-full-access", false)))
        assertEquals(3, ComposerPermissionPolicy.ids.size)
        assertTrue(ComposerPermissionPolicy.allowed(":workspace", profiles, requirements))
        assertFalse(ComposerPermissionPolicy.allowed(":danger-full-access", profiles, requirements))
        assertFalse(ComposerPermissionPolicy.allowed("custom", profiles, requirements))
        assertFalse(ComposerPermissionPolicy.allowed(":workspace", emptyList(), requirements))
    }
    @Test fun persistedDefaultUsesOfficialWriteAndReadbackAndRejectsOverrides() = runBlocking {
        var persisted = ":read-only"
        val calls = mutableListOf<String>()
        val request: suspend (String, JSONObject) -> JSONObject = { method, params ->
            calls += method
            when (method) {
                "config/batchWrite" -> {
                    val edit = params.getJSONArray("edits").getJSONObject(0)
                    assertEquals("default_permissions", edit.getString("keyPath"))
                    assertEquals("replace", edit.getString("mergeStrategy"))
                    assertTrue(params.getBoolean("reloadUserConfig"))
                    persisted = edit.getString("value")
                    JSONObject().put("status", "ok")
                }
                "config/read" -> {
                    assertEquals("/workspace", params.getString("cwd"))
                    JSONObject().put("config", JSONObject().put("default_permissions", persisted))
                }
                else -> error("unexpected RPC: $method")
            }
        }
        val readback = SecurityPermissionController.saveDefault(":workspace", "/workspace", request = request)
        assertEquals(":workspace", ComposerPermissionPolicy.selected(readback))
        assertEquals(listOf("config/batchWrite", "config/read"), calls)
        // A fresh read uses the persisted value, not a retained UI selection.
        val reopened = request("config/read", JSONObject().put("cwd", "/workspace"))
        assertEquals(":workspace", ComposerPermissionPolicy.selected(reopened))
        try {
            SecurityPermissionController.saveDefault(":danger-full-access", "/workspace") { method, _ ->
                if (method == "config/batchWrite") JSONObject().put("status", "ok")
                else JSONObject().put("config", JSONObject().put("default_permissions", ":workspace"))
            }
            fail("mismatched effective profile was accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("不一致"))
        }
    }

    @Test fun savedProfileReadbackDrivesNewAndSubsequentTurnRequests() {
        for (id in ComposerPermissionPolicy.ids) {
            val saved = JSONObject().put("config", JSONObject().put("default_permissions", id))
            assertEquals(id, ComposerPermissionPolicy.selected(saved))
            assertEquals(id, ComposerPermissionPolicy.apply(JSONObject(), ComposerPermissionPolicy.selected(saved))
                .getString("permissions"))
        }
        assertNull(ComposerPermissionPolicy.selected(null))
        assertFalse(ComposerPermissionPolicy.apply(JSONObject(), "custom").has("permissions"))
    }
}
