package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAvailableServicesTest {
    private fun server(name: String, status: String, auth: String = "oAuth") =
        JSONObject().put("name", name).put("runtimeStatus", status)
            .put("authStatus", auth)
            .put("tools", JSONObject().put("ready_tool", JSONObject()))

    @Test fun onlyLiveAndAuthorizedConnectionsAppearInTheList() {
        val items = listOf(
            server("ready", "connected"),
            server("notStarted", "notStarted"),
            server("starting", "starting"),
            server("authenticationRequired", "authenticationRequired", "notLoggedIn"),
            server("failed", "failed"),
            server("cancelled", "cancelled"),
            server("disabled", "disabled"),
            server("unknown", ""),
            server("missingAuthorization", "connected", "notLoggedIn"),
            server("", "connected"),
            server("loading", "ready")
        )
        assertEquals(listOf("ready"), McpAvailableServices.visible(items)
            .map { it.getString("name") })
    }

    @Test fun aBrokenToolCatalogIsNotAdvertisedAsUsable() {
        val broken = server("broken", "connected").put("toolsError", "discovery failed")
        assertFalse(McpAvailableServices.isUsable(broken))
        assertFalse(McpAvailableServices.isUsable(
            JSONObject().put("name", "no-capabilities").put("runtimeStatus", "connected")))
        assertTrue(McpAvailableServices.isUsable(
            JSONObject().put("name", "prompt-only").put("runtimeStatus", "connected")
                .put("serverCapabilities", JSONObject().put("prompts", JSONObject()))))
        val resourceOnly = server("resource", "connected")
            .put("toolsError", "tool catalog unavailable")
            .put("resources", JSONArray().put(JSONObject().put("uri", "mcp://article")))
        assertTrue(McpAvailableServices.isUsable(resourceOnly))
        val templates = server("template", "connected")
            .put("toolsError", "tool catalog unavailable")
            .put("resourceTemplates", JSONArray().put(JSONObject().put("uriTemplate", "mcp://{id}")))
        assertTrue(McpAvailableServices.isUsable(templates))
        assertTrue(McpAvailableServices.isUsable(
            server("none", "connected").put("toolsError", JSONObject.NULL)))
        assertFalse(McpAvailableServices.isUsable(
            server("literal-error", "connected").put("toolsError", "null")))
    }

    @Test fun visibleServicesAreSortedAndInternalNamesAreReadable() {
        val list = McpAvailableServices.visible(listOf(
            server("zeta", "connected"),
            server("codex_apps", "connected", "bearerToken"),
            server("Alpha", "connected")
        ))
        assertEquals(listOf("Alpha", "ChatGPT 扩展服务", "zeta"),
            list.map(McpAvailableServices::displayName))
        assertEquals("未命名服务",
            McpAvailableServices.displayName(JSONObject().put("name", "")))
    }
}
