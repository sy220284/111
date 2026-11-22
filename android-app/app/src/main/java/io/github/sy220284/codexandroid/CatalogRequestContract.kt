package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** The initial read, explicit refresh and error retry must use identical scope. */
object CatalogRequestContract {
    /** Avoid a second initial RPC while a catalog is already cached or loading. */
    fun shouldLoadCatalog(key: String, loaded: Set<String>, loading: Set<String>): Boolean =
        key !in loaded && key !in loading

    fun workspace(cwd: String): JSONObject {
        require(cwd.isNotBlank()) { "必须选择有效的工作区" }
        return JSONObject().put("cwds", JSONArray().put(cwd))
    }

    fun accountSessions(): JSONObject = JSONObject().put("refreshWorkspaceMetadata", false)

    fun mcpStatus(threadId: String?): JSONObject = JSONObject()
        .put("detail", "full")
        .apply {
            threadId?.takeIf { it.isNotBlank() }?.let { put("threadId", it) }
        }
}
