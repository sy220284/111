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
        invalidateModels()
        vm.data.value = vm.data.value - "providers" - "skills"
        scope.launch {
            // Let account and thread restoration take priority over catalog I/O.
            delay(650)
            if (vm.bridge.status.value != EngineStatus.Connected) return@launch
            ensure("models", "model/list")
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

    private fun invalidateModels() {
        val keys = (vm.data.value.keys + vm.loading.value + vm.errors.value.keys).filter {
            it.startsWith("composerModels:") || it == "models"
        }.toSet()
        invalidate(keys)
    }

    private fun invalidate(keys: Set<String>) {
        keys.forEach { requests.cancel(it) }
        vm.data.value = vm.data.value - keys
        vm.errors.value = vm.errors.value - keys
        vm.loading.value = vm.loading.value - keys
    }

    /** Refresh only the failed task's provider, including offscreen completions.
     * Resolve an unknown route from the native thread only on the failure path. */
    fun modelRejected(threadId: String?, provider: String? = null) {
        val resolved = ComposerProviderScope.provider(provider) ?: if (threadId != null && threadId == vm.activeThread.value)
            ComposerProviderScope.provider(vm.activeModelProvider.value) else null
        if (resolved != null) {
            refreshRejectedProvider(resolved)
            return
        }
        if (threadId.isNullOrBlank()) return
        val owner = vm.captureTaskOwner() ?: return
        scope.launch {
            try {
                val thread = owner.request(vm, "thread/read", JSONObject().put("threadId", threadId).put("includeTurns", false)).getJSONObject("thread")
                owner.requireCurrent(vm)
                refreshRejectedProvider(requireNotNull(ComposerProviderScope.provider(thread.optString("modelProvider"))) {
                    "内核未返回失败会话的服务商"
                })
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (owner.current(vm)) vm.actionFeedback.tryEmit(ActionFeedbackReceipt(
                    "读取失败会话的模型目录未成功：" + (failure.message ?: "服务商未确认"), isError = true))
            }
        }
    }

    private fun refreshRejectedProvider(provider: String) {
        val scoped = ComposerProviderScope.catalogKey(provider)
        val defaultProvider = ComposerProviderScope.provider(vm.data.value["providers"]?.optString("defaultProviderId"))
        val keys = if (defaultProvider == null || defaultProvider == provider) setOf(scoped, "models") else setOf(scoped)
        val key = ComposerProviderScope.catalogKey(provider, defaultProvider)
        invalidate(keys - key)
        vm.refresh(key, "model/list", ComposerProviderScope.request(provider, forceRefresh = true))
    }

    fun accountChanged() {
        accountRevisionState.value += 1
        vm.threadOrganization.invalidate()
        vm.invalidateAccountReceipts()
        vm.sessions.invalidateIdentity()
        vm.historyTurnOutcomes = null
        refreshRouting()
    }

    // Workspace routing can settle without changing the authenticated owner.
    fun refreshRouting() {
        providerChanged()
        vm.refresh("skills", "skills/list",
            CatalogRequestContract.workspace(vm.workingDirectory))
    }

    fun providerChanged() {
        invalidateModels()
        invalidate(setOf("providers"))
        vm.refresh("providers", "modelProvider/list")
        // A provider-list error must not prevent reading the native default catalog.
        ensure("models", "model/list")
    }

    /** Any authoritative provider-list receipt may complete route discovery.
     * Do not rely on a particular callback surviving a newer request. */
    fun providerCatalogRead() {
        val defaultProvider = ComposerProviderScope.provider(vm.data.value["providers"]?.optString("defaultProviderId"))
        if (defaultProvider != null) {
            val obsolete = ComposerProviderScope.catalogKey(defaultProvider)
            invalidate(setOf(obsolete))
        }
        ensure("models", "model/list")
        val key = vm.composerCatalogKey()
        if (key != "models") ensure(key, "model/list", ComposerProviderScope.request(vm.currentComposerProvider()))
    }
}
