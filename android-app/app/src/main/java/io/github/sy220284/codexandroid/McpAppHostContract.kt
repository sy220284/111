package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Native host for the MCP Apps 2026-01-26 JSON-RPC transport. No arbitrary native RPC names. */
internal class McpAppHostContract(private val call: JSONObject) {
    private var state = 0
    val version = "2026-01-26"
    val server = call.getString("server")
    val environment = call.optString("environmentId").takeUnless { call.isNull("environmentId") }
    val uri = call.optJSONObject("mcpAppUi")?.optString("resourceUri")?.takeIf(String::isNotBlank)
        ?: call.optString("mcpAppResourceUri")
    val scopedTools = server != "codex_apps" && call.isNull("appContext")
    val ready get() = state == 2
    fun notification(method: String, params: JSONObject) = JSONObject().put("jsonrpc", "2.0")
        .put("method", method).put("params", params)
    fun response(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))
    fun initialize(params: JSONObject, theme: String): JSONObject {
        require(state == 0 && params.optString("protocolVersion") == version &&
            params.optJSONObject("appInfo") != null && params.optJSONObject("appCapabilities") != null) {
            "MCP App 协议版本或初始化数据不受支持"
        }
        state = 1
        val capabilities = JSONObject().put("openLinks", JSONObject()).put("sandbox", JSONObject())
        if (scopedTools) capabilities.put("serverTools", JSONObject()).put("serverResources", JSONObject())
        return JSONObject().put("protocolVersion", version)
            .put("hostInfo", JSONObject().put("name", "111 Android").put("version", "1.0"))
            .put("hostCapabilities", capabilities)
            .put("hostContext", JSONObject().put("platform", "mobile").put("theme", theme)
                .put("displayMode", "fullscreen").put("availableDisplayModes", JSONArray().put("fullscreen"))
                .put("deviceCapabilities", JSONObject().put("touch", true).put("hover", false)))
    }
    fun initialized(): List<JSONObject> {
        require(state == 1)
        state = 2
        val result = call.optJSONObject("result") ?: JSONObject().put("content", JSONArray())
        return listOf(notification("ui/notifications/tool-input", JSONObject()
            .put("arguments", call.optJSONObject("arguments") ?: JSONObject())),
            notification("ui/notifications/tool-result", result))
    }
    fun scoped(params: JSONObject, thread: String): JSONObject = params.put("threadId", thread)
        .put("server", server).apply { environment?.let { put("environmentId", it) } }
    fun catalogRequest(thread: String) = JSONObject().put("threadId", thread).put("serverName", server)
        .put("detail", "full").put("limit", 20).apply { environment?.let { put("environmentId", it) } }
    fun registration(result: JSONObject): JSONObject {
        val values = result.getJSONArray("data")
        val matches = (0 until values.length()).map { values.getJSONObject(it) }.filter {
            it.optString("name") == server &&
                it.optString("environmentId").takeUnless { _ -> it.isNull("environmentId") } == environment
        }
        require(matches.size == 1) { "工具服务归属无法确认" }
        return matches.single()
    }
    fun allowedTool(registration: JSONObject, name: String): Boolean {
        if (!scopedTools || name.isBlank()) return false
        val tool = registration.optJSONObject("tools")?.optJSONObject(name) ?: return false
        if (tool.optString("name") != name) return false
        val ui = tool.optJSONObject("_meta")?.optJSONObject("ui")
        if (ui == null || ui.isNull("visibility")) return true
        val visibility = ui.optJSONArray("visibility") ?: return false
        return (0 until visibility.length()).any { visibility.optString(it) == "app" }
    }
    fun allowedResource(registration: JSONObject, requested: String): Boolean {
        if (!scopedTools || requested.isBlank()) return false
        if (requested == uri) return true
        val resources = registration.optJSONArray("resources") ?: return false
        return (0 until resources.length()).any { resources.optJSONObject(it)?.optString("uri") == requested }
    }
    companion object {
        const val MAX_HTML = 2 * 1024 * 1024
        const val MAX_MESSAGE = 256 * 1024
        fun html(result: JSONObject, uri: String): String? {
            val values = result.getJSONArray("contents")
            val matches = (0 until values.length()).map { values.getJSONObject(it) }.filter {
                it.optString("uri") == uri && it.optString("mimeType").replace(" ", "")
                    .equals("text/html;profile=mcp-app", true)
            }
            if (matches.isEmpty()) return null
            require(matches.size == 1)
            val source = matches.single().opt("text") as? String ?: error("MCP App HTML 资源不是文本")
            require(source.toByteArray(Charsets.UTF_8).size in 1..MAX_HTML) { "MCP App 资源过大" }
            return source
        }
        fun externalLink(url: String): Boolean = try {
            val parsed = java.net.URI(url)
            parsed.scheme == "https" && parsed.host != null && parsed.rawUserInfo == null
        } catch (_: java.net.URISyntaxException) { false }
    }
}
