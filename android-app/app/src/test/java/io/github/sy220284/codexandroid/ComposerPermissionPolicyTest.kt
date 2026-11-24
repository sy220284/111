package io.github.sy220284.codexandroid

import org.json.JSONObject
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
