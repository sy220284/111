package io.github.sy220284.codexandroid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Owns extension-directory cache boundaries for the entire process. */
internal class ExtensionCatalogController(
    private val vm: CodexViewModel,
    private val requests: LatestRequestGate
) {
    val epoch = MutableStateFlow(0L)
    var appAuthorizationPending = false
    private var lastAppDirectoryEvent: String? = null

    fun observeWorkspace(scope: CoroutineScope) {
        var previous = vm.activeWorkspace.value
        scope.launch {
            vm.activeWorkspace.collect { workspace ->
                if (workspace != previous) {
                    previous = workspace
                    invalidate(pluginsOnly = true)
                }
            }
        }
    }

    fun refreshAfterAppAuthorization() {
        if (!appAuthorizationPending || vm.bridge.status.value != EngineStatus.Connected) return
        appAuthorizationPending = false
        refreshApps(force = true)
    }

    fun appDirectoryChanged(params: JSONObject) {
        if (vm.bridge.status.value != EngineStatus.Connected) return
        val event = params.optJSONArray("data")?.toString() ?: return
        if (event == lastAppDirectoryEvent) return
        lastAppDirectoryEvent = event
        val data = vm.data.value
        val loading = vm.loading.value
        // An event during a list request belongs to that snapshot; an external event
        // refreshes both catalogs without recursively reading identical notifications.
        if ("apps" in loading) {
            if (("appInstalled" in data || "appInstalled" in loading) && "appInstalled" !in loading)
                vm.refresh("appInstalled", "app/installed", JSONObject().put("forceRefresh", true))
        } else if ("apps" in data || "appInstalled" in data || "appInstalled" in loading) {
            refreshApps(force = false)
        }
    }

    fun refreshApps(force: Boolean, replaceInFlight: Boolean = force) {
        if (vm.bridge.status.value != EngineStatus.Connected) return
        if (replaceInFlight || "appInstalled" !in vm.loading.value)
            vm.refresh("appInstalled", "app/installed", JSONObject().put("forceRefresh", force))
        if (replaceInFlight || "apps" !in vm.loading.value)
            vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("forceRefetch", force))
    }

    fun loadMoreApps(cursor: String) {
        if (vm.bridge.status.value != EngineStatus.Connected || cursor.isBlank() ||
            "apps" in vm.loading.value || ExtensionCatalogPolicy.cursor(vm.data.value["apps"]) != cursor) return
        vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("cursor", cursor))
    }

    fun refreshPluginCatalog(key: String, workspace: String, force: Boolean = false) {
        if (vm.bridge.status.value != EngineStatus.Connected || workspace != vm.activeWorkspace.value) return
        val params = CatalogRequestContract.workspace(workspace)
        if (key == "plugins") vm.refresh(key, "plugin/list", params.put("forceRefetch", force))
        else if (key == "installedPlugins") vm.refresh(key, "plugin/installed", params)
    }

    fun changePlugin(method: String, params: JSONObject, workspace: String) {
        if (workspace != vm.activeWorkspace.value || vm.bridge.status.value != EngineStatus.Connected ||
            "pluginMutation" in vm.loading.value) return
        val after: () -> Unit = {
            if (workspace == vm.activeWorkspace.value) {
                invalidatePluginSearchResults()
                refreshPluginCatalog("plugins", workspace, true)
                refreshPluginCatalog("installedPlugins", workspace)
            }
        }
        when (method) {
            "plugin/install" -> vm.rpc("pluginMutation", "plugin/install", params, after = after)
            "plugin/uninstall" -> vm.rpc("pluginMutation", "plugin/uninstall", params, after = after)
            else -> error("不支持的插件变更操作：$method")
        }
    }

    fun changeApp(id: String, enabled: Boolean) {
        if (vm.bridge.status.value != EngineStatus.Connected || "appMutation" in vm.loading.value) return
        vm.rpc("appMutation", "config/value/write", JSONObject().put("keyPath", "apps." + id + ".enabled")
            .put("value", enabled).put("mergeStrategy", "replace"), after = { refreshApps(force = true) })
    }

    fun invalidate(pluginsOnly: Boolean = false, preserveAuthorization: Boolean = false) {
        if (!pluginsOnly) {
            lastAppDirectoryEvent = null
            if (!preserveAuthorization) appAuthorizationPending = false
        }
        invalidateKeys { ExtensionCatalogPolicy.isScopedKey(it, pluginsOnly) }
    }

    fun invalidatePluginSearchResults() = invalidateKeys {
        it.startsWith("pluginSearch:") || it.startsWith("pluginDetail:") || it.startsWith("pluginSkill:")
    }

    private fun invalidateKeys(predicate: (String) -> Boolean) {
        val keys = (vm.data.value.keys + vm.loading.value + vm.errors.value.keys)
            .filter(predicate).toSet()
        keys.forEach { requests.cancel(it) }
        vm.data.value = vm.data.value - keys
        vm.loading.value = vm.loading.value - keys
        vm.errors.value = vm.errors.value - keys
        epoch.value += 1
    }
}
