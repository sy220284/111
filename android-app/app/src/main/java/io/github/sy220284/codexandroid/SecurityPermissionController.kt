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
    suspend fun saveDefault(bridge: CodexConnection, profileId: String, cwd: String? = null,
        isCurrent: () -> Boolean = { true }): JSONObject =
        saveDefault(profileId, cwd, isCurrent = isCurrent) { method, params -> bridge.request(method, params) }

    internal suspend fun saveDefault(profileId: String, cwd: String?,
        request: suspend (String, JSONObject) -> JSONObject): JSONObject =
        saveDefault(profileId, cwd, { true }, request)

    private suspend fun saveDefault(profileId: String, cwd: String?,
        isCurrent: () -> Boolean, request: suspend (String, JSONObject) -> JSONObject): JSONObject {
        require(profileId in ComposerPermissionPolicy.ids) { "未知权限方案" }
        val params = JSONObject().put("edits", JSONArray().put(JSONObject()
            .put("keyPath", "default_permissions").put("value", profileId)
            .put("mergeStrategy", "replace"))).put("reloadUserConfig", true)
        return ConfigWriteTransaction.execute(
            write = { request("config/batchWrite", params) },
            read = { request("config/read", JSONObject().put("includeLayers", true).apply {
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
            }) },
            isCurrent = isCurrent,
            verify = { write, read ->
                check(write.optString("status") == "ok") { "权限设置受到更高优先级策略覆盖" }
                check(ComposerPermissionPolicy.selected(read) == profileId) {
                    "权限已写入，但实际生效方案与所选不一致"
                }
            })
    }
}

internal fun CodexViewModel.refreshComposerPermissions() {
    if (bridge.status.value != EngineStatus.Connected || securitySaveBusy.value) return
    val cwd = activeWorkspace.value
    data.value = data.value + ("composerPermissionWorkspace" to JSONObject().put("cwd", cwd))
    refresh("composerPermissionConfig", "config/read", JSONObject().put("cwd", cwd))
    refresh("securityRequirements", "configRequirements/read", null)
    refresh("permissionProfiles", "permissionProfile/list", JSONObject().put("cwd", cwd))
}

internal fun CodexViewModel.composerPermissionBlockReason(): String? = when {
    !globalWorkSnapshot().canStartNewTask -> "本机数据正在备份或恢复"
    bridge.status.value != EngineStatus.Connected || connectionRestarter.busy.value -> "运行核心尚未连接"
    threadSwitching.value || firstThreadStarting.value || running.value -> "当前任务结束后可调整权限"
    configSnapshotBusy(loading.value, securitySaveBusy.value) -> "已有配置操作正在处理，请稍候"
    data.value["composerPermissionWorkspace"]?.optString("cwd") != activeWorkspace.value ||
        loading.value.any { it in setOf("composerPermissionConfig", "permissionProfiles", "securityRequirements") } ->
        "正在读取当前项目权限，请稍候"
    data.value["composerPermissionConfig"]?.optJSONObject("config") == null ||
        data.value["permissionProfiles"] == null || data.value["securityRequirements"] == null ||
        errors.value.keys.any { it in setOf("composerPermissionConfig", "permissionProfiles", "securityRequirements") } ->
        "权限配置或策略尚未完整读取，请刷新重试"
    else -> null
}

internal fun CodexViewModel.chooseComposerPermission(id: String) {
    val reason = composerPermissionBlockReason() ?: if (!ComposerPermissionPolicy.allowed(id,
        records(data.value["permissionProfiles"]), data.value["securityRequirements"])) "所选权限当前不可用" else null
    if (reason != null) {
        errors.value = errors.value + ("securitySave" to reason)
        return
    }
    val cwd = activeWorkspace.value
    val generation = configGeneration
    securitySaveBusy.value = true
    errors.value = errors.value - "securitySave"
    viewModelScope.launch {
        try {
            val verified = SecurityPermissionController.saveDefault(bridge, id, cwd) { configGeneration == generation }
            if (activeWorkspace.value == cwd && configGeneration == generation)
                data.value = data.value + ("composerPermissionConfig" to verified)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            errors.value = errors.value + ("securitySave" to (failure.message ?: "权限保存或回读失败"))
            if (activeWorkspace.value == cwd && configGeneration == generation)
                data.value = data.value - "composerPermissionConfig"
        } finally {
            securitySaveBusy.value = false
            // The write targets the user config; invalidate both global and cwd-scoped snapshots.
            refresh("config", "config/read", JSONObject().put("includeLayers", true))
            refreshComposerPermissions()
        }
    }
}
