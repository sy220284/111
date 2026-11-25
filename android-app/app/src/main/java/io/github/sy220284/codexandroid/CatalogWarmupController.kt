package io.github.sy220284.codexandroid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Coordinates catalog prefetch independently of the connection's critical startup path. */
internal class CatalogWarmupController(
    private val vm: CodexViewModel,
    private val scope: CoroutineScope
) {
    fun connected() {
        // After reconnect, cached listings from the old core session are stale.
        vm.invalidateModelCatalogs(includeDefault = false)
        vm.data.value = vm.data.value - "providers" - "skills"
        scope.launch {
            // Let account and thread restoration take priority over catalog I/O.
            delay(650)
            if (vm.bridge.status.value != EngineStatus.Connected) return@launch
            ensure("providers", "modelProvider/list")
            ensure("skills", "skills/list",
                CatalogRequestContract.workspace(vm.workingDirectory))
        }
    }

    fun ensure(key: String, method: String, params: JSONObject = JSONObject()) {
        if (CatalogRequestContract.shouldLoadCatalog(
                key, vm.data.value.keys, vm.loading.value
            )) vm.refresh(key, method, params)
    }

    fun accountChanged() {
        vm.invalidateModelCatalogs()
        vm.refresh("models", "model/list")
        if (vm.composerCatalogKey() != "models") vm.refreshComposerCatalog()
        vm.refresh("providers", "modelProvider/list")
        vm.refresh("skills", "skills/list",
            CatalogRequestContract.workspace(vm.workingDirectory))
    }
}
