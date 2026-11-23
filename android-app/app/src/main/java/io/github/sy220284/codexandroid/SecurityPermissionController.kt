package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** One verified path for permission writes; never treat an overridden write as effective. */
object SecurityPermissionController {
    suspend fun saveDefault(bridge: CodexConnection, profileId: String): JSONObject {
        val params = JSONObject()
            .put("edits", JSONArray().put(JSONObject()
                .put("keyPath", "default_permissions")
                .put("value", profileId)
                .put("mergeStrategy", "replace")))
            .put("reloadUserConfig", true)
        val write = bridge.request("config/batchWrite", params)
        check(write.optString("status") == "ok") {
            "权限设置受到更高优先级策略覆盖"
        }
        val read = bridge.request("config/read", JSONObject().put("includeLayers", true))
        check(read.getJSONObject("config").optString("default_permissions") == profileId) {
            "权限已写入，但实际生效方案与所选不一致"
        }
        return read
    }
}
