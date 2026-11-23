package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * A status card is visible only when its MCP server has a usable live connection.
 * An OAuth configuration or cached tool catalog by itself is not a connection.
 */
internal object McpAvailableServices {
    fun visible(servers: List<JSONObject>): List<JSONObject> =
        servers.filter(::isUsable).sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { displayName(it) }
        )

    fun displayName(server: JSONObject): String {
        val original = server.optString("name")
        return if (original == "codex_apps") "ChatGPT 扩展服务"
        else PresentationPolicy.meaningful(original).ifEmpty { "未命名服务" }
    }

    fun isUsable(server: JSONObject): Boolean {
        if (PresentationPolicy.meaningful(server.optString("name")).isEmpty()) return false
        if (server.optString("runtimeStatus") != "connected") return false
        if (server.optString("authStatus") == "notLoggedIn") return false

        val toolsError = server.optString("toolsError")
        if (toolsError.isBlank() || toolsError == "null") return true

        // A tool catalog may fail while independently advertised resources remain usable.
        return (server.optJSONArray("resources")?.length() ?: 0) > 0 ||
            (server.optJSONArray("resourceTemplates")?.length() ?: 0) > 0
    }
}
