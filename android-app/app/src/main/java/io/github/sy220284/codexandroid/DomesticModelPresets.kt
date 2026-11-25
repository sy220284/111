package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONArray

/** One bundled catalog shared with the Rust provider layer. Presets are not authorization. */
internal data class DomesticModelPreset(
    val id: String, val name: String, val baseUrl: String, val envKey: String,
    val plan: String, val protocol: String, val models: List<String>, val catalog: String = "manual"
) {
    val needsWorkspace get() = id == "qwen-cn"
    fun workspaceId(endpoint: String): String? {
        if (!needsWorkspace) return null
        return Regex("^https://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.cn-beijing\\.maas\\.aliyuncs\\.com/compatible-mode/v1$")
            .matchEntire(endpoint.trim().trimEnd('/'))?.groupValues?.get(1)
    }
    fun workspaceEndpoint(workspace: String): String =
        "https://$workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
    fun matchesEndpoint(value: String): Boolean = value.trim().trimEnd('/') == baseUrl || workspaceId(value) != null
    fun liveCatalog(endpoint: String): Boolean = if (needsWorkspace) workspaceId(endpoint) != null else catalog != "manual"

    fun ownsProvider(value: org.json.JSONObject): Boolean =
        matchesEndpoint(value.optString("baseUrl")) &&
            value.optJSONObject("settings")?.optString("type") == "customGateway" &&
            value.optJSONObject("settings")?.optJSONObject("auth")?.let {
                it.optString("mode") == "environmentVariable" && it.optString("envKey") == envKey
            } == true
}

internal object DomesticModelPresets {
    fun load(context: Context): List<DomesticModelPreset> =
        context.assets.open("domestic-providers.json").bufferedReader().use { parse(it.readText()) }

    fun parse(json: String): List<DomesticModelPreset> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val models = item.getJSONArray("models")
            DomesticModelPreset(
                item.getString("id"), item.getString("name"), item.getString("baseUrl"),
                item.getString("envKey"), item.getString("plan"), item.getString("protocol"),
                (0 until models.length()).map { models.getJSONObject(it).getString("id") },
                item.getString("catalog")
            )
        }.also { presets ->
            require(presets.map { it.id }.distinct().size == presets.size)
            require(presets.all { it.protocol == "responses" && it.baseUrl.startsWith("https://") })
        }
    }
}
