package io.github.sy220284.codexandroid

import org.json.JSONObject

/** One capability decision for the composer, running turn and independent analysis.
 * Unknown capability remains eligible for the native core's learned negotiation.
 * A confirmed mismatch never silently strips media or transfers it to another provider. */
internal object MediaRoutePolicy {
    data class Decision(val missing: Set<String>, val compatibleModels: List<String>) {
        val acceptsNative get() = missing.isEmpty()
        val reason get() = "当前模型不接收" + missing.sorted().joinToString("、", transform = ::label) +
            "，原附件已保留。" + if (compatibleModels.isEmpty()) "可明确选择媒体处理方式或更换模型。"
            else "当前服务商可选择：" + compatibleModels.take(3).joinToString("、") + "。"
    }
    private fun label(kind: String): String = when (kind) {
        "image" -> "图片"; "audio" -> "音频"; "video" -> "视频"; else -> kind
    }
    fun missing(known: Set<String>, required: Set<String>): Set<String> =
        if (known.isEmpty()) emptySet() else required - known

    fun decide(catalog: List<JSONObject>, modelId: String, required: Set<String>): Decision {
        val known = ComposerModelPolicy.model(catalog, modelId)?.let(ComposerModelPolicy::inputKinds).orEmpty().toSet()
        val missing = missing(known, required)
        val alternatives = if (missing.isEmpty()) emptyList() else catalog.filter {
            !it.optBoolean("hidden") && ComposerModelPolicy.inputKinds(it).toSet().containsAll(required)
        }.map { it.optString("id").ifBlank { it.optString("model") } }.filter(String::isNotBlank).distinct()
        return Decision(missing, alternatives)
    }
}
