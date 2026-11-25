package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Unknown backend metadata stays in the original execution record without speculative labels. */
internal object ImageGenerationPresentation {
    fun summary(raw: JSONObject): String? = buildList {
        (raw.opt("transparentBackground") as? Boolean)?.let {
            add(if (it) "透明背景" else "不透明背景")
        }
        raw.optJSONObject("imageMetadata")?.length()?.takeIf { it > 0 }?.let { add("生成信息 $it 项 · 执行详情可查") }
    }.joinToString(" · ").takeIf { it.isNotBlank() }
}
