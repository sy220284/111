package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpAppHostContractTest {
    private fun call(server: String = "local") = JSONObject().put("server", server).put("tool", "start")
        .put("mcpAppUi", JSONObject().put("resourceUri", "ui://local/view"))
        .put("arguments", JSONObject().put("id", 1)).put("result", JSONObject().put("content", JSONArray()))
    @Test fun handshakeAndScopedToolCatalog() {
        val host = McpAppHostContract(call())
        assertFalse(host.ready)
        host.initialize(JSONObject().put("protocolVersion", "2026-01-26")
            .put("appInfo", JSONObject()).put("appCapabilities", JSONObject()), "dark")
        assertEquals(listOf("ui/notifications/tool-input", "ui/notifications/tool-result"),
            host.initialized().map { it.getString("method") })
        assertTrue(host.ready)
        val tool = JSONObject().put("name", "refresh").put("_meta", JSONObject().put("ui",
            JSONObject().put("visibility", JSONArray().put("model"))))
        val registration = JSONObject().put("name", "local").put("tools", JSONObject().put("refresh", tool))
        assertFalse(host.allowedTool(registration, "refresh"))
        tool.getJSONObject("_meta").getJSONObject("ui").put("visibility", JSONArray().put("app"))
        assertTrue(host.allowedTool(registration, "refresh"))
        assertFalse(host.allowedTool(registration, "other"))
        assertFalse(McpAppHostContract(call("codex_apps")).allowedTool(registration, "refresh"))
        assertFalse(McpAppHostContract.externalLink("javascript:alert(1)"))
        assertTrue(McpAppHostContract.externalLink("https://example.com/"))
    }
}
