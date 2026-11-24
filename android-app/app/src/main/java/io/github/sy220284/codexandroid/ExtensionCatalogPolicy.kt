package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Directory snapshots survive refresh, but never cross account/workspace boundaries. */
internal object ExtensionCatalogPolicy {
    private val catalogMethods = mapOf("apps" to "app/list", "appInstalled" to "app/installed",
        "plugins" to "plugin/list", "installedPlugins" to "plugin/installed")
    fun retainsSnapshot(key: String, method: String) = catalogMethods[key] == method
    fun isScopedKey(key: String, pluginsOnly: Boolean): Boolean =
        key in setOf("plugins", "installedPlugins") || key.startsWith("pluginSearch:") ||
            key.startsWith("pluginDetail:") || key.startsWith("pluginSkill:") ||
            (!pluginsOnly && key in setOf("apps", "appInstalled"))
    fun cursor(response: JSONObject?) = PresentationPolicy.meaningful(response?.optString("nextCursor"))
    fun appendApps(previous: JSONObject?, next: JSONObject): JSONObject {
        val items = (records(previous) + records(next)).filter { it.optString("id").isNotBlank() }
            .associateBy { it.optString("id") }.values
        return JSONObject(next.toString()).put("data", JSONArray().apply { items.forEach { put(it) } })
    }
    fun apps(directory: JSONObject?, installed: JSONObject?): List<JSONObject> {
        val known = records(directory).filter { it.optString("id").isNotBlank() }
        val ids = known.map { it.optString("id") }.toSet()
        val installedOnly = records(installed).filter {
            it.optString("id").isNotBlank() && it.optString("id") !in ids
        }.map {
            JSONObject().put("id", it.optString("id"))
                .put("name", PresentationPolicy.meaningful(it.optString("runtimeName"))
                    .ifBlank { "已安装应用" })
                .put("isEnabled", it.optBoolean("enabled"))
        }
        return (known + installedOnly).distinctBy { it.optString("id") }
    }
    fun appStatus(app: JSONObject, installed: JSONObject?, verified: Boolean): String {
        val active = records(installed).firstOrNull { it.optString("id") == app.optString("id") }
        return when {
            !app.optBoolean("isEnabled", true) || active?.opt("enabled") == false -> "已停用"
            !verified -> "状态待核对"
            active?.optBoolean("callable") == true -> "已连接"
            app.has("isAccessible") && !app.optBoolean("isAccessible") -> "待授权"
            active?.optBoolean("enabled") == true -> "等待工具就绪"
            else -> "尚未连接"
        }
    }
    fun pluginName(item: JSONObject) = PresentationPolicy.meaningful(
        item.optJSONObject("interface")?.optString("displayName"))
        .ifBlank { PresentationPolicy.meaningful(item.optString("name")) }
    fun marketName(item: JSONObject) = PresentationPolicy.meaningful(
        item.optJSONObject("interface")?.optString("displayName"))
        .ifBlank { PresentationPolicy.meaningful(item.optString("name")) }
    fun plugins(market: JSONObject, installedOnly: Boolean): List<JSONObject> =
        records(market).filter { !installedOnly || it.optBoolean("installed") }
    fun matches(item: JSONObject, query: String): Boolean {
        val term = query.trim()
        if (term.isEmpty()) return true
        return listOf(pluginName(item), item.optString("name"), item.optString("keywords"),
            item.optJSONObject("interface")?.optString("shortDescription").orEmpty())
            .any { it.contains(term, ignoreCase = true) }
    }
}
