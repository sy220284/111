package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Persist explicit overrides by conversation. Blank inherits the core's actual default. */
data class ComposerTaskOptions(
    val model: String = "",
    val mode: String = "default",
    val effort: String = ""
) {
    fun encode() = JSONObject().put("model", model).put("mode", mode).put("effort", effort)
    companion object {
        fun decode(value: JSONObject?): ComposerTaskOptions = ComposerTaskOptions(
            value?.optString("model").orEmpty(),
            value?.optString("mode")?.takeIf { it in setOf("default", "plan") } ?: "default",
            value?.optString("effort").orEmpty()
        )
    }
}

internal object ComposerModelPolicy {
    fun model(catalog: List<JSONObject>, id: String): JSONObject? =
        catalog.firstOrNull { !it.optBoolean("hidden") &&
            (it.optString("id") == id || it.optString("model") == id) }
    fun efforts(catalog: List<JSONObject>, modelId: String): List<String> =
        if (modelId.isBlank()) emptyList() else
            model(catalog, modelId)?.optJSONArray("supportedReasoningEfforts")?.objects()
                .orEmpty().map { it.optString("reasoningEffort") }.filter { it.isNotBlank() }.distinct()
    fun requireEffort(catalog: List<JSONObject>, modelId: String, effort: String) {
        require(effort.isBlank() || effort in efforts(catalog, modelId)) { "当前模型不支持所选思考强度，请重新选择" }
    }
}

internal fun CodexViewModel.restoreComposerOptions(threadId: String?) {
    val options = composerDrafts.value[ThreadComposerDrafts.key(threadId)]?.options ?: ComposerTaskOptions()
    modelId.value = options.model
    selectedMode.value = options.mode
}
internal fun CodexViewModel.changeComposerOptions(transform: (ComposerTaskOptions) -> ComposerTaskOptions) {
    val thread = activeThread.value
    if (threadSwitching.value || firstThreadStarting.value || running.value) return
    composerDrafts.update { drafts ->
        val original = drafts[ThreadComposerDrafts.key(thread)]?.options ?: ComposerTaskOptions()
        ThreadComposerDrafts.options(drafts, thread, transform(original))
    }
    restoreComposerOptions(thread)
}
internal fun CodexViewModel.chooseComposerModel(id: String) =
    changeComposerOptions { it.copy(model = id, effort = if (it.model == id) it.effort else "") }
internal fun CodexViewModel.chooseComposerMode(id: String) =
    changeComposerOptions { it.copy(mode = id) }
internal fun CodexViewModel.chooseComposerEffort(id: String) =
    changeComposerOptions {
        ComposerModelPolicy.requireEffort(records(data.value["models"]), it.model, id)
        it.copy(effort = id)
    }
