package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object ComposerPermissionPolicy {
    val ids = listOf(":read-only", ":workspace", ":danger-full-access")
    fun selected(config: JSONObject?): String? =
        config?.optJSONObject("config")?.optString("default_permissions")?.takeIf { it in ids }
    fun allowed(id: String, profiles: List<JSONObject>, requirements: JSONObject?): Boolean {
        if (id !in ids || profiles.none { it.optString("id") == id && it.optBoolean("allowed") }) return false
        val limits = requirements?.optJSONObject("requirements")?.optJSONObject("allowedPermissionProfiles")
        return limits == null || !limits.has(id) || limits.optBoolean(id)
    }
    fun apply(params: JSONObject, id: String?): JSONObject =
        params.apply { if (id in ids) put("permissions", id) }
}

/** Persist to the core, and report success only after reading the effective configuration. */
object SecurityPermissionController {
    suspend fun saveDefault(bridge: CodexConnection, profileId: String, cwd: String? = null): JSONObject {
        require(profileId in ComposerPermissionPolicy.ids) { "未知权限方案" }
        val params = JSONObject().put("edits", JSONArray().put(JSONObject()
            .put("keyPath", "default_permissions").put("value", profileId)
            .put("mergeStrategy", "replace"))).put("reloadUserConfig", true)
        val write = bridge.request("config/batchWrite", params)
        check(write.optString("status") == "ok") { "权限设置受到更高优先级策略覆盖" }
        val read = bridge.request("config/read", JSONObject().put("includeLayers", true).apply {
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        })
        check(ComposerPermissionPolicy.selected(read) == profileId) {
            "权限已写入，但实际生效方案与所选不一致"
        }
        return read
    }
}

internal fun CodexViewModel.refreshComposerPermissions() {
    if (bridge.status.value != EngineStatus.Connected || securitySaveBusy.value) return
    val cwd = activeWorkspace.value
    refresh("composerPermissionConfig", "config/read", JSONObject().put("cwd", cwd))
    refresh("securityRequirements", "configRequirements/read", null)
    refresh("permissionProfiles", "permissionProfile/list", JSONObject().put("cwd", cwd))
}

internal fun CodexViewModel.chooseComposerPermission(id: String) {
    if (securitySaveBusy.value || threadSwitching.value || firstThreadStarting.value || running.value ||
        connectionRestarter.busy.value || bridge.status.value != EngineStatus.Connected ||
        !globalWorkSnapshot().canStartNewTask || configSnapshotBusy(loading.value, securitySaveBusy.value) ||
        loading.value.any { it in setOf("composerPermissionConfig", "permissionProfiles", "securityRequirements") }) return
    if (!ComposerPermissionPolicy.allowed(id, records(data.value["permissionProfiles"]),
            data.value["securityRequirements"])) return
    val cwd = activeWorkspace.value
    securitySaveBusy.value = true
    errors.value = errors.value - "securitySave"
    viewModelScope.launch {
        try {
            val verified = SecurityPermissionController.saveDefault(bridge, id, cwd)
            if (activeWorkspace.value == cwd) data.value = data.value + ("composerPermissionConfig" to verified)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            errors.value = errors.value + ("securitySave" to (failure.message ?: "权限保存或回读失败"))
            data.value = data.value - "composerPermissionConfig"
        } finally {
            securitySaveBusy.value = false
            if (activeWorkspace.value != cwd || errors.value["securitySave"] != null) refreshComposerPermissions()
        }
    }
}
