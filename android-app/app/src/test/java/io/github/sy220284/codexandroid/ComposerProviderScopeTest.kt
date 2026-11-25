package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposerProviderScopeTest {
    private val catalog = listOf(JSONObject().put("id", "deepseek-flash").put("isDefault", true))

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
        val a = ComposerModelPolicy.choose(ComposerTaskOptions(), "provider", "a", catalog)
        val remembered = ComposerModelPolicy.withEffort(a, "high")
        val b = ComposerModelPolicy.choose(remembered, "provider", "b", catalog)
        assertEquals("", b.effort)
        val restored = ComposerTaskOptions.decode(b.encode())
        assertEquals("high", ComposerModelPolicy.choose(restored, "provider", "a", catalog).effort)
        assertEquals("", ComposerModelPolicy.choose(restored, "other", "a", catalog).effort)
        assertEquals("", ComposerModelPolicy.choose(restored, "provider", "a", listOf(JSONObject().put("id", "a"))).effort)
    }
}
