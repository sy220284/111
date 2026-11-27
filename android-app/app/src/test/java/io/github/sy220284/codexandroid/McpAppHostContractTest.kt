package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpAppHostContractTest {
    @Test fun htmlAcceptsBoundedUtf8TextAndBlobWithoutAmbiguousEncoding() {
        val html = "<!doctype html><p>应用</p>"
        val resource = JSONObject().put("uri", "ui://local/view").put("mimeType", "text/html;profile=mcp-app")
        val result = JSONObject().put("contents", JSONArray().put(resource))
        resource.put("text", html)
        assertEquals(html, McpAppHostContract.html(result, "ui://local/view"))
        resource.remove("text")
        resource.put("blob", java.util.Base64.getEncoder().encodeToString(html.toByteArray()))
        assertEquals(html, McpAppHostContract.html(result, "ui://local/view"))
        resource.put("text", html)
        assertThrows(IllegalArgumentException::class.java) { McpAppHostContract.html(result, "ui://local/view") }
        resource.remove("text")
        resource.put("blob", "!!!!")
        assertThrows(IllegalArgumentException::class.java) { McpAppHostContract.html(result, "ui://local/view") }
        resource.put("blob", "/w==")
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { McpAppHostContract.html(result, "ui://local/view") }
        resource.put("blob", "A".repeat(((McpAppHostContract.MAX_HTML + 2) / 3) * 4 + 1))
        assertThrows(IllegalArgumentException::class.java) { McpAppHostContract.html(result, "ui://local/view") }
    }
    @Test fun hostedResourceRefreshUsesExactOriginWithoutEnablingUnattributedTools() {
        val host = McpAppHostContract(call("codex_apps"), "original-call")
        assertFalse(host.scopedTools)
        val request = host.resourceRequest("thread", "ui://local/view")
        assertEquals("original-call", request.getString("originCallId"))
        assertEquals("thread", request.getString("threadId"))
        assertThrows(IllegalArgumentException::class.java) { host.resourceRequest("thread", "ui://other/view") }
        assertThrows(IllegalArgumentException::class.java) { McpAppHostContract(call("codex_apps")).resourceRequest("thread", "ui://local/view") }
    }
    @Test fun paginationValidatesEntireCatalogBeforeAuthorizing() = kotlinx.coroutines.runBlocking {
        val host = McpAppHostContract(call())
        val local = JSONObject().put("name", "local").put("tools", JSONObject())
        val result = host.readRegistration("thread") { request ->
            if (request.isNull("cursor")) JSONObject().put("data", JSONArray()).put("nextCursor", "second")
            else JSONObject().put("data", JSONArray().put(local)).put("nextCursor", JSONObject.NULL)
        }
        assertEquals("local", result.getString("name"))
        assertTrue(runCatching {
            host.readRegistration("thread") { JSONObject().put("data", JSONArray()).put("nextCursor", "loop") }
        }.isFailure)
        assertTrue(runCatching {
            host.readRegistration("thread") { request ->
                JSONObject().put("data", JSONArray().put(local)).put("nextCursor",
                    if (request.isNull("cursor")) "second" else JSONObject.NULL)
            }
        }.isFailure)
    }
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
