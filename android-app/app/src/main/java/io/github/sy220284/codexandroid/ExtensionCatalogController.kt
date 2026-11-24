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
        if (!appAuthorizationPending) return
        appAuthorizationPending = false
        if (vm.bridge.status.value != EngineStatus.Connected) return
        vm.refresh("appInstalled", "app/installed", JSONObject().put("forceRefresh", true))
        vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("forceRefetch", true))
    }

    fun invalidate(pluginsOnly: Boolean = false) =
        invalidateKeys { ExtensionCatalogPolicy.isScopedKey(it, pluginsOnly) }

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
