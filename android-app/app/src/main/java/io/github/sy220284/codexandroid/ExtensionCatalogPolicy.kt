package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Directory snapshots survive refresh, but never cross account/workspace boundaries. */
internal object ExtensionCatalogPolicy {
    private val catalogMethods = mapOf("apps" to "app/list", "appInstalled" to "app/installed",
        "plugins" to "plugin/list", "installedPlugins" to "plugin/installed")
    fun retainsSnapshot(key: String, method: String) = catalogMethods[key] == method
    fun isScopedKey(key: String, pluginsOnly: Boolean): Boolean =
        key in setOf("plugins", "installedPlugins", "pluginMutation") || key.startsWith("pluginSearch:") ||
            key.startsWith("pluginDetail:") || key.startsWith("pluginSkill:") ||
            (!pluginsOnly && key in setOf("apps", "appInstalled", "appMutation"))
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
    fun appStatus(app: JSONObject, installed: JSONObject?, verified: Boolean): String =
        appStatusForRecord(app, records(installed).firstOrNull { it.optString("id") == app.optString("id") }, verified)

    fun appStatusForRecord(app: JSONObject, active: JSONObject?, verified: Boolean): String {
        return when {
            !verified -> "状态待核对"
            active?.opt("enabled") == false -> "已停用"
            active?.optBoolean("callable") == true -> "已连接"
            active == null && !app.optBoolean("isEnabled", true) -> "已停用"
            app.has("isAccessible") && !app.optBoolean("isAccessible") -> "待授权"
            active?.optBoolean("enabled") == true -> "等待工具就绪"
            else -> "尚未连接"
        }
    }
    fun remotePluginId(item: JSONObject): String =
        PresentationPolicy.meaningful(item.optString("remotePluginId"))

    fun pluginIdentity(market: JSONObject, item: JSONObject): Pair<String, String> =
        market.optString("name") to item.optString("id").ifBlank { item.optString("name") }

    fun installedIdentities(snapshot: JSONObject?): Set<Pair<String, String>> =
        snapshot?.optJSONArray("marketplaces")?.let { markets ->
            (0 until markets.length()).mapNotNull { markets.optJSONObject(it) }
                .flatMap { market -> plugins(market, true).map { pluginIdentity(market, it) } }.toSet()
        }.orEmpty()

    fun isInstalled(market: JSONObject, item: JSONObject, installed: Set<Pair<String, String>>,
                    verified: Boolean): Boolean =
        if (verified) pluginIdentity(market, item) in installed
        else item.optBoolean("installed") || pluginIdentity(market, item) in installed

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
