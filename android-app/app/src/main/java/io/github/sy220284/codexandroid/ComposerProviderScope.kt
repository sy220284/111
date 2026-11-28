package io.github.sy220284.codexandroid

import org.json.JSONObject

/** A model slug has meaning only within its provider. Keep settings and conversation catalogs separate. */
internal object ComposerProviderScope {
    fun provider(raw: String?): String? = raw?.takeIf { it.isNotBlank() && it != "null" }

    fun request(provider: String? = null, forceRefresh: Boolean = false): JSONObject =
        JSONObject().apply {
            provider?.let { put("modelProvider", it) }
            if (forceRefresh) put("forceRefresh", true)
        }

    fun catalogKey(provider: String?, defaultProvider: String? = null): String =
        if (provider == null || provider == defaultProvider) "models" else "composerModels:$provider"

    fun isModelRejection(message: String, code: String? = null): Boolean =
        code == "modelNotFound" || message.contains("model is not supported when using Codex with a ChatGPT account", ignoreCase = true) ||
            message.contains("model_not_in_accessible_catalog", ignoreCase = true)

    fun resolve(threadProvider: String?, draftProvider: String?, configuredProvider: String): String {
        require(configuredProvider.isNotBlank()) { "当前默认服务商尚未读取" }
        if (threadProvider != null) {
            require(draftProvider == null || draftProvider == threadProvider) {
                "所选模型属于其他服务商，请重新选择；历史会话继续使用原服务商"
            }
            return threadProvider
        }
        return draftProvider ?: configuredProvider
    }

    fun initialModel(catalog: List<JSONObject>, requested: String, configured: String?, requireCatalog: Boolean): String {
        if (requested.isNotBlank()) return requested
        if (!requireCatalog) return ""
        if (configured != null) return configured
        return catalog.firstOrNull { !it.optBoolean("hidden") && it.optBoolean("isDefault") }?.let { it.optString("id").ifBlank { it.optString("model") } }
            ?: catalog.firstOrNull { !it.optBoolean("hidden") }?.let { it.optString("id").ifBlank { it.optString("model") } }
            ?: error("当前服务商没有可用模型，请检查授权或模型目录")
    }

    /** Running queue/steer use the actual thread model, not a next-turn draft override. */
    suspend fun requireExistingMedia(threadId: String, required: Set<String>,
        rpc: suspend (String, JSONObject) -> JSONObject) {
        if (required.isEmpty()) return
        val thread = rpc("thread/read", JSONObject().put("threadId", threadId)).getJSONObject("thread")
        val provider = requireNotNull(provider(thread.optString("modelProvider"))) { "当前会话服务商未确认" }
        val model = requireNotNull(ComposerProviderScope.provider(thread.optString("model"))) { "当前会话模型未确认，请重新打开会话" }
        val catalog = records(rpc("model/list", request(provider)))
        ComposerModelPolicy.requireMediaInput(catalog, model, required)
    }

    data class Submission(val provider: String, val model: String, val catalog: List<JSONObject>, val effectiveConfig: JSONObject?)

    suspend fun submission(
        config: JSONObject, submittedThread: String?, chosenModel: String, chosenMode: String,
        chosenOptions: ComposerTaskOptions, hasImages: Boolean = false, mediaKinds: Set<String> = emptySet(),
        knownThreadProvider: String? = null, rpc: suspend (String, JSONObject) -> JSONObject
    ): Submission {
        val configuredProvider = ComposerProviderScope.provider(config.optString("model_provider")) ?: "openai"
        val capturedProvider = ComposerProviderScope.provider(knownThreadProvider)
        val needsInheritedModel = chosenModel.isBlank() && (chosenMode == "plan" || hasImages || mediaKinds.isNotEmpty())
        val existing = submittedThread?.takeIf { capturedProvider == null || needsInheritedModel }?.let {
            rpc("thread/read", JSONObject().put("threadId", it).put("includeTurns", false)).getJSONObject("thread")
        }
        val existingProvider = if (submittedThread == null) null else if (existing == null) capturedProvider else
            requireNotNull(ComposerProviderScope.provider(existing.optString("modelProvider"))) { "内核未返回会话所属服务商，请重新打开会话" }
        val provider = ComposerProviderScope.resolve(existingProvider,
            ComposerProviderScope.provider(chosenOptions.modelProvider), configuredProvider)
        // account/read describes the globally configured provider, not necessarily
        // the selected thread/workspace. The native manager owns account discovery
        // and the actual default; do not infer authorization from another provider.
        val configuredModel = if (submittedThread == null && provider == configuredProvider)
            ComposerProviderScope.provider(config.optString("model")) else null
        val inherited = existing?.let { ComposerProviderScope.provider(it.optString("model")) }
        val selectDefault = submittedThread == null && provider != configuredProvider && chosenModel.isBlank()
        val needsPlanDefault = chosenMode == "plan" && chosenModel.isBlank() &&
            inherited == null && configuredModel == null
        // Explicit text tasks already carry an exact model/provider. Directory
        // absence cannot prove that inference is unauthorized (aliases, custom models).
        val requiresCatalog = hasImages || mediaKinds.isNotEmpty() || selectDefault || needsPlanDefault
        val catalog = if (requiresCatalog)
            records(rpc("model/list", ComposerProviderScope.request(provider)))
            else emptyList()
        val requestModel = if (submittedThread == null)
            ComposerProviderScope.initialModel(catalog, chosenModel, configuredModel, selectDefault)
            else chosenModel
        val imageModel = requestModel.ifBlank {
            if (existing != null) {
                ComposerProviderScope.provider(existing.optString("model")).orEmpty()
            } else {
                configuredModel ?: catalog.firstOrNull { !it.optBoolean("hidden") && it.optBoolean("isDefault") }
                    ?.let { it.optString("id").ifBlank { it.optString("model") } }.orEmpty()
            }
        }
        ComposerModelPolicy.requireMediaInput(catalog, imageModel, mediaKinds + (if (hasImages) setOf("image") else emptySet()))
        val effectiveConfig = if (chosenMode == "plan") JSONObject(config.toString()).apply {
            if (submittedThread != null) {
                put("model", inherited ?: JSONObject.NULL)
            } else if (provider != configuredProvider) {
                put("model", JSONObject.NULL)
            }
            if (submittedThread != null || provider != configuredProvider) put("plan_mode_reasoning_effort", JSONObject.NULL)
        } else null
        return Submission(provider, requestModel, catalog, effectiveConfig)
    }
}
