package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Persist explicit overrides by conversation. Blank inherits the core's actual default. */
data class ComposerTaskOptions(
    val model: String = "",
    val mode: String = "default",
    val effort: String = "",
    val modelProvider: String = ""
) {
    fun encode() = JSONObject().put("model", model).put("mode", mode).put("effort", effort).put("modelProvider", modelProvider)
    companion object {
        fun decode(value: JSONObject?): ComposerTaskOptions = ComposerTaskOptions(
            value?.optString("model").orEmpty(),
            value?.optString("mode")?.takeIf { it in setOf("default", "plan") } ?: "default",
            value?.optString("effort").orEmpty(),
            value?.optString("modelProvider").orEmpty()
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
    fun requireEffort(catalog: List<JSONObject>, modelId: String, effort: String) {
        require(effort.isBlank() || effort in efforts(catalog, modelId)) { "当前模型不支持所选思考强度，请重新选择" }
    }
}

internal fun CodexViewModel.restoreComposerOptions(threadId: String?) {
    val options = composerDrafts.value[composerOwner(threadId)]?.options ?: ComposerTaskOptions()
    modelId.value = options.model
    selectedMode.value = options.mode
}
internal fun CodexViewModel.changeComposerOptions(transform: (ComposerTaskOptions) -> ComposerTaskOptions) {
    val thread = activeThread.value
    if (threadSwitching.value || firstThreadStarting.value || running.value) return
    composerDrafts.update { drafts ->
        val original = drafts[composerOwner(thread)]?.options ?: ComposerTaskOptions()
        ThreadComposerDrafts.options(drafts, composerOwner(thread), transform(original))
    }
    restoreComposerOptions(thread)
}
internal fun CodexViewModel.chooseComposerModel(id: String) =
    changeComposerOptions {
        val provider = if (id.isBlank()) it.modelProvider else currentComposerProvider().orEmpty()
        it.copy(model = id, modelProvider = provider,
            effort = if (it.model == id && it.modelProvider == provider) it.effort else "")
    }
internal fun CodexViewModel.chooseComposerProvider(id: String) {
    if (activeThread.value != null) return
    changeComposerOptions { it.copy(modelProvider = id, model = "", effort = "") }
}
internal fun CodexViewModel.chooseComposerMode(id: String) =
    changeComposerOptions { it.copy(mode = id) }
internal fun CodexViewModel.chooseComposerEffort(id: String) =
    changeComposerOptions {
        ComposerModelPolicy.requireEffort(records(data.value[composerCatalogKey()]), it.model, id)
        it.copy(effort = id)
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
