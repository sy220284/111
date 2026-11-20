package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Explicit, validated read-only request contracts for inspectable extensions. */
object InspectionContracts {
    fun mcpResource(server: String, uri: String): JSONObject {
        require(server.isNotBlank()) { "缺少 MCP 服务名称" }
        require(uri.isNotBlank()) { "缺少资源地址" }
        return JSONObject().put("server", server).put("uri", uri)
    }

    fun plugin(
        name: String,
        marketplace: String,
        path: String
    ): JSONObject {
        require(name.isNotBlank()) { "缺少插件名称" }
        require(path.isNotBlank() || marketplace.isNotBlank()) { "缺少插件来源" }
        val result = JSONObject().put("pluginName", name)
        if (path.isNotBlank()) result.put("marketplacePath", path)
        else result.put("remoteMarketplaceName", marketplace)
        return result
    }
}
