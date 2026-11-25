package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposerProviderScopeTest {
    private val catalog = listOf(JSONObject().put("id", "deepseek-flash").put("isDefault", true))

    @Test fun composerOptionAccessMatchesRuntimeAndNextTurnRules() {
        for (hasThread in listOf(false, true))
            for (running in listOf(false, true))
                for (allowRunning in listOf(false, true)) {
                    assertEquals(!running || (hasThread && allowRunning),
                        ComposerOptionAccess.canChange(hasThread, running, allowRunning,
                            false, false, false, false))
                    for (blocked in 0..3) {
                        assertFalse(ComposerOptionAccess.canChange(hasThread, running, allowRunning,
                            blocked == 0, blocked == 1, blocked == 2, blocked == 3))
                    }
                }
    }

    @Test fun providerOwnsModelAndThread() {
        assertEquals("deepseek", ComposerProviderScope.resolve("deepseek", null, "openai"))
        assertEquals("deepseek", ComposerProviderScope.resolve(null, "deepseek", "openai"))
        assertEquals("openai", ComposerProviderScope.resolve(null, null, "openai"))
        assertThrows(IllegalArgumentException::class.java) {
            ComposerProviderScope.resolve("deepseek", "openai", "openai")
        }
        assertEquals("deepseek", ComposerProviderScope.request("deepseek").getString("modelProvider"))
        assertFalse(ComposerProviderScope.request("openai").has("forceRefresh"))
        assertTrue(ComposerProviderScope.request("openai", forceRefresh = true).getBoolean("forceRefresh"))
        assertFalse(ComposerProviderScope.request(forceRefresh = true).has("modelProvider"))
        assertNotEquals(ComposerProviderScope.catalogKey("deepseek"), ComposerProviderScope.catalogKey("openai"))
        assertEquals("deepseek", ComposerTaskOptions.decode(ComposerTaskOptions("deepseek-flash", modelProvider = "deepseek").encode()).modelProvider)
    }

    @Test fun imageAdmissionAllowsUnknownAndBlocksConfirmedTextOnlyWithoutDeletingDraft() {
        val unknown = JSONObject().put("id", "unknown")
        val image = JSONObject().put("id", "vision").put("inputModalities", org.json.JSONArray().put("text").put("image"))
        val text = JSONObject().put("id", "text").put("inputModalities", org.json.JSONArray().put("text"))
        val models = listOf(unknown, image, text)
        ComposerModelPolicy.requireImageInput(models, "unknown", true)
        ComposerModelPolicy.requireImageInput(models, "vision", true)
        ComposerModelPolicy.requireImageInput(models, "text", false)
        assertThrows(IllegalArgumentException::class.java) {
            ComposerModelPolicy.requireImageInput(models, "text", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnRequestContract.build("thread", "读图", "text", "default", models, listOf("/image.png"))
        }
        val request = TurnRequestContract.build("thread", "读图", "vision", "default", models, listOf("/image.png"))
        assertEquals("localImage", request.getJSONArray("input").getJSONObject(1).getString("type"))
    }

    @Test fun neverSendsAnotherProvidersModel() {
        ComposerProviderScope.requireModel(catalog, "deepseek-flash", "")
        assertThrows(IllegalArgumentException::class.java) {
            ComposerProviderScope.requireModel(catalog, "gpt", "")
        }
        assertEquals("deepseek-flash", ComposerProviderScope.initialModel(catalog, "", null, true))
        assertThrows(IllegalArgumentException::class.java) {
            ComposerProviderScope.initialModel(catalog, "", "gpt", true)
        }
        assertThrows(IllegalStateException::class.java) {
            ComposerProviderScope.initialModel(emptyList(), "", null, true)
        }
    }

    @Test fun modelEffortPreferenceRoundTripsAndRespectsCurrentCatalog() {
        val row = JSONObject().put("id", "a").put("supportedReasoningEfforts",
            org.json.JSONArray().put(JSONObject().put("reasoningEffort", "high")))
        val catalog = listOf(row, JSONObject().put("id", "b"))
        val legacy = ComposerTaskOptions("a", effort = "high", modelProvider = "provider")
        val legacyB = ComposerModelPolicy.choose(legacy, "provider", "b", catalog)
        assertEquals("high", ComposerModelPolicy.choose(legacyB, "provider", "a", catalog).effort)
        val a = ComposerModelPolicy.choose(ComposerTaskOptions(), "provider", "a", catalog)
        val remembered = ComposerModelPolicy.withEffort(a, "high")
        val b = ComposerModelPolicy.choose(remembered, "provider", "b", catalog)
        assertEquals("", b.effort)
        val restored = ComposerTaskOptions.decode(b.encode())
        assertEquals("high", ComposerModelPolicy.choose(restored, "provider", "a", catalog).effort)
        assertEquals("", ComposerModelPolicy.choose(restored, "other", "a", catalog).effort)
        assertEquals("", ComposerModelPolicy.choose(restored, "provider", "a", listOf(JSONObject().put("id", "a"))).effort)
    }

    @Test fun recentOrderIsProviderScopedAndPersistsAcrossReopen() {
        val catalog = listOf(JSONObject().put("id", "a"), JSONObject().put("id", "b"))
        val a = ComposerModelPolicy.choose(ComposerTaskOptions(), "p", "a", catalog)
        val b = ComposerModelPolicy.choose(a, "p", "b", catalog)
        val restored = ComposerTaskOptions.decode(b.encode())
        assertEquals(0, ComposerModelPolicy.recentRank(restored, "p", "b"))
        assertEquals(1, ComposerModelPolicy.recentRank(restored, "p", "a"))
        assertEquals(Int.MAX_VALUE, ComposerModelPolicy.recentRank(restored, "other", "a"))
        val model = JSONObject().put("inputModalities", org.json.JSONArray().put("text").put("image"))
        assertEquals(listOf("text", "image"), ComposerModelPolicy.inputKinds(model))
        assertTrue(ComposerModelPolicy.inputKinds(JSONObject()).isEmpty())
    }
}
