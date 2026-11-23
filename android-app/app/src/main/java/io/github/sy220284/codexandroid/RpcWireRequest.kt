package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * Wire format is owned here, not by individual screens. A null parameter means
 * no `params` member, required for app-server methods typed as Option<()>.
 * An explicitly supplied empty object remains an object for other methods.
 */
internal object RpcWireRequest {
    fun envelope(method: String, id: Long, params: JSONObject?): JSONObject =
        JSONObject().put("method", method).put("id", id).apply {
            if (params != null) put("params", params)
        }
}
