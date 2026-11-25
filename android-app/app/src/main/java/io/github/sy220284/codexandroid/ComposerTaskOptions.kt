package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Persist explicit overrides by conversation. Blank inherits the core's actual default. */
data class ComposerTaskOptions(
    val model: String = "",
    val mode: String = "default",
    val effort: String = "",
    val modelProvider: String = "",
    val modelEfforts: Map<String, String> = emptyMap()
) {
    fun encode() = JSONObject().put("model", model).put("mode", mode).put("effort", effort).put("modelProvider", modelProvider)
        .put("modelEfforts", JSONObject(modelEfforts))
    companion object {
        fun decode(value: JSONObject?): ComposerTaskOptions = ComposerTaskOptions(
            value?.optString("model").orEmpty(),
            value?.optString("mode")?.takeIf { it in setOf("default", "plan") } ?: "default",
            value?.optString("effort").orEmpty(),
            value?.optString("modelProvider").orEmpty(),
            value?.optJSONObject("modelEfforts")?.let { map ->
                map.keys().asSequence().associateWith { map.optString(it) }
            }.orEmpty()
        )
    }
}

internal object ComposerModelPolicy {
    fun model(catalog: List<JSONObject>, id: String): JSONObject? =
        catalog.firstOrNull { !it.optBoolean("hidden") &&
            (it.optString("id") == id || it.optString("model") == id) }
    fun efforts(catalog: List<JSONObject>, modelId: String): List<String> =
        if (modelId.isBlank()) emptyList() else
            model(catalog, modelId)?.optJSONArray("supportedReasoningEfforts")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("reasoningEffort") }
            }.orEmpty().filter { it.isNotBlank() && it != "ultra" }.distinct()
    fun choose(options: ComposerTaskOptions, provider: String, id: String,
        catalog: List<JSONObject>): ComposerTaskOptions {
        val key = "$provider/$id"
        val remembered = options.modelEfforts[key].orEmpty()
        val previous = if (options.model == id && options.modelProvider == provider) options.effort else remembered
        return options.copy(model = id, modelProvider = provider,
            effort = previous.takeIf { it in efforts(catalog, id) }.orEmpty())
    }
    fun withEffort(options: ComposerTaskOptions, effort: String): ComposerTaskOptions {
        val key = "${options.modelProvider}/${options.model}"
        return options.copy(effort = effort,
            modelEfforts = if (effort.isBlank()) options.modelEfforts - key else options.modelEfforts + (key to effort))
    }
    fun requireEffort(catalog: List<JSONObject>, modelId: String, effort: String) {
        require(effort.isBlank() || effort in efforts(catalog, modelId)) { "当前模型不支持所选思考强度，请重新选择" }
    }
}

internal fun CodexViewModel.restoreComposerOptions(threadId: String?) {
    val options = composerDrafts.value[composerOwner(threadId)]?.options ?: ComposerTaskOptions()
    modelId.value = options.model
    selectedMode.value = options.mode
}
internal fun CodexViewModel.changeComposerOptions(allowRunning: Boolean = false, transform: (ComposerTaskOptions) -> ComposerTaskOptions) {
    val thread = activeThread.value
    if (threadSwitching.value || firstThreadStarting.value || (!allowRunning && running.value) ||
        connectionRestarter.busy.value || AppDataTransfer.state.value.busy) return
    composerDrafts.update { drafts ->
        val original = drafts[composerOwner(thread)]?.options ?: ComposerTaskOptions()
        ThreadComposerDrafts.options(drafts, composerOwner(thread), transform(original))
    }
    restoreComposerOptions(thread)
}
internal fun CodexViewModel.chooseComposerModel(id: String) =
    changeComposerOptions(allowRunning = true) {
        val provider = if (id.isBlank()) it.modelProvider else currentComposerProvider().orEmpty()
        ComposerModelPolicy.choose(it, provider, id, records(data.value[composerCatalogKey()]))
    }
internal fun CodexViewModel.chooseComposerProvider(id: String) {
    if (activeThread.value != null) return
    changeComposerOptions { it.copy(modelProvider = id, model = "", effort = "") }
}
internal fun CodexViewModel.chooseComposerMode(id: String) =
    changeComposerOptions { it.copy(mode = id) }
internal fun CodexViewModel.chooseComposerEffort(id: String) =
    changeComposerOptions(allowRunning = true) {
        ComposerModelPolicy.requireEffort(records(data.value[composerCatalogKey()]), it.model, id)
        ComposerModelPolicy.withEffort(it, id)
    }

internal fun CodexViewModel.currentComposerProvider(): String? =
    if (activeThread.value != null) ComposerProviderScope.provider(activeModelProvider.value)
    else ComposerProviderScope.provider(composerDrafts.value[composerOwner(null)]?.options?.modelProvider)
        ?: ComposerProviderScope.provider(data.value["providers"]?.optString("defaultProviderId"))

internal fun CodexViewModel.composerCatalogKey(): String =
    ComposerProviderScope.catalogKey(
        if (activeThread.value != null) currentComposerProvider()
        else ComposerProviderScope.provider(composerDrafts.value[composerOwner(null)]?.options?.modelProvider)
    )

internal fun CodexViewModel.refreshComposerCatalog(forceRefresh: Boolean = false) {
    val key = composerCatalogKey()
    if (key in loading.value) return
    val provider = currentComposerProvider().takeIf { key != "models" }
    refresh(key, "model/list", ComposerProviderScope.request(provider, forceRefresh))
}
