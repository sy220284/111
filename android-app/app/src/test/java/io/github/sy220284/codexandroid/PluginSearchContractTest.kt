package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginSearchContractTest {
    @Test fun queryIsNormalizedAndCursorIsForwarded() {
        val request = PluginSearchContract.params("  代码审查  ", "next-14", 20)
        assertEquals("代码审查", request.getString("searchTerm"))
        assertEquals("next-14", request.getString("cursor"))
        assertEquals(20, request.getInt("limit"))
    }

    @Test fun queryRejectsBlankAndExcessivePageSize() {
        try {
            PluginSearchContract.params("   ")
            fail("空搜索词应被拒绝")
        } catch (_: IllegalArgumentException) {}
        try {
            PluginSearchContract.params("插件", limit = 101)
            fail("超限分页应被拒绝")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun searchResultsPreserveExactMarketAndPluginIdentity() {
        val summary = JSONObject().put("id", "plugin-2").put("name", "reviewer")
        val result = JSONObject().put("marketplaceName", "my-market")
            .put("marketplacePath", "/plugins/market")
            .put("plugin", summary)
        val response = JSONObject().put("data", JSONArray().put(result)
            .put(JSONObject().put("marketplaceName", "invalid-no-plugin")))
        val parsed = PluginSearchContract.entries(response)
        assertEquals(1, parsed.size)
        assertEquals("plugin-2", parsed.single().plugin.getString("id"))
        assertEquals("my-market", parsed.single().marketplace.getString("name"))
        assertEquals("/plugins/market", parsed.single().marketplace.getString("path"))
    }

    @Test fun missingResponseDoesNotInventSearchHits() {
        assertTrue(PluginSearchContract.entries(null).isEmpty())
        assertTrue(PluginSearchContract.entries(JSONObject()).isEmpty())
    }
}
