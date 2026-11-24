package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionCatalogPolicyTest {
    @Test fun installedAppsAreVisibleWithoutTheRemoteDirectory() {
        val installed = JSONObject().put("apps", JSONArray().put(JSONObject()
            .put("id", "x").put("runtimeName", "示例").put("enabled", true).put("callable", true)))
        val app = ExtensionCatalogPolicy.apps(null, installed).single()
        assertEquals("示例", app.optString("name"))
        assertEquals("已连接", ExtensionCatalogPolicy.appStatus(app, installed, true))
        assertEquals("状态待核对", ExtensionCatalogPolicy.appStatus(app, installed, false))
        val detailed = JSONObject().put("data", JSONArray().put(JSONObject()
            .put("id", "x").put("name", "正式名称").put("isEnabled", true)))
        assertEquals("正式名称", ExtensionCatalogPolicy.apps(detailed, installed).single().optString("name"))
    }

    @Test fun paginationDeduplicatesAndNullCursorStops() {
        fun response(vararg ids: String) = JSONObject().put("data", JSONArray().apply {
            ids.forEach { put(JSONObject().put("id", it)) }
        })
        val first = response("a", "b").put("nextCursor", "next")
        val second = response("b", "c").put("nextCursor", JSONObject.NULL)
        val merged = ExtensionCatalogPolicy.appendApps(first, second)
        assertEquals(listOf("a", "b", "c"), records(merged).map { it.optString("id") })
        assertEquals("", ExtensionCatalogPolicy.cursor(merged))
    }

    @Test fun installedListFiltersSuggestionsAndSearchMatchesMetadata() {
        val installed = JSONObject().put("name", "fixture").put("installed", true)
            .put("keywords", JSONArray().put("设计"))
        val suggestion = JSONObject().put("name", "other").put("installed", false)
        val market = JSONObject().put("plugins", JSONArray().put(installed).put(suggestion))
        assertEquals(listOf(installed), ExtensionCatalogPolicy.plugins(market, true))
        assertEquals(2, ExtensionCatalogPolicy.plugins(market, false).size)
        assertTrue(ExtensionCatalogPolicy.matches(installed, "设计"))
        assertFalse(ExtensionCatalogPolicy.matches(suggestion, "设计"))
    }

    @Test fun snapshotsAreRetainedOnlyForReadsAndClearedAtScopeBoundary() {
        assertTrue(ExtensionCatalogPolicy.retainsSnapshot("apps", "app/list"))
        assertFalse(ExtensionCatalogPolicy.retainsSnapshot("apps", "config/value/write"))
        assertTrue(ExtensionCatalogPolicy.isScopedKey("pluginSearch:design:0", true))
        assertFalse(ExtensionCatalogPolicy.isScopedKey("appInstalled", true))
        assertTrue(ExtensionCatalogPolicy.isScopedKey("appInstalled", false))
        assertFalse(ExtensionCatalogPolicy.isScopedKey("models", false))
    }
}
