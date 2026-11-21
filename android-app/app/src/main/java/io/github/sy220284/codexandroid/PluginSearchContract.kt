package io.github.sy220284.codexandroid

import org.json.JSONObject

/** 实验性插件搜索的正式参数与响应结构，以真实市场来源完成详情和安装。 */
data class PluginSearchHit(val marketplace: JSONObject, val plugin: JSONObject)

object PluginSearchContract {
    fun params(searchTerm: String, cursor: String? = null, limit: Int = 25): JSONObject {
        val term = searchTerm.trim()
        require(term.isNotEmpty()) { "请输入插件名称或关键字" }
        require(limit in 1..100) { "插件搜索单页最多读取 100 条" }
        return JSONObject().put("searchTerm", term).put("limit", limit).apply {
            if (!cursor.isNullOrBlank()) put("cursor", cursor)
        }
    }

    fun entries(response: JSONObject?): List<PluginSearchHit> {
        val items = response?.optJSONArray("data") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val entry = items.optJSONObject(index) ?: return@mapNotNull null
            val plugin = entry.optJSONObject("plugin") ?: return@mapNotNull null
            val name = entry.optString("marketplaceName")
            if (name.isBlank()) return@mapNotNull null
            val market = JSONObject().put("name", name)
            entry.optString("marketplacePath").takeIf { it.isNotBlank() }
                ?.let { market.put("path", it) }
            PluginSearchHit(market, plugin)
        }
    }
}
