package io.github.sy220284.codexandroid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Coordinates catalog prefetch independently of the connection's critical startup path. */
internal class CatalogWarmupController(
    private val vm: CodexViewModel,
    private val scope: CoroutineScope,
    private val requests: LatestRequestGate
) {
    private val accountRevisionState = MutableStateFlow(0L)
    val accountRevision = accountRevisionState.asStateFlow()

    fun connected() {
        // After reconnect, cached listings from the old core session are stale.
        invalidateModels(includeDefault = false)
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

    private fun invalidateModels(includeDefault: Boolean = true) {
        val keys = (vm.data.value.keys + vm.loading.value + vm.errors.value.keys).filter {
            it.startsWith("composerModels:") || (includeDefault && it == "models")
        }.toSet()
        keys.forEach { requests.cancel(it) }
        vm.data.value = vm.data.value - keys
        vm.errors.value = vm.errors.value - keys
        vm.loading.value = vm.loading.value - keys
    }

    fun accountChanged() {
        accountRevisionState.value += 1
        vm.threadOrganization.invalidate()
        invalidateModels()
        vm.refresh("models", "model/list")
        if (vm.composerCatalogKey() != "models") vm.refreshComposerCatalog()
        vm.refresh("providers", "modelProvider/list")
        vm.refresh("skills", "skills/list",
            CatalogRequestContract.workspace(vm.workingDirectory))
    }
}
