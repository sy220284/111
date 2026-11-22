package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONObject

/** Locally persisted unsent drafts; text is never sent anywhere by this store. */
internal object DraftPersistence {
    private const val STORE = "codex-unsent-drafts"
    private const val KEY = "drafts"

    fun load(context: Context): Map<String, ComposerDraft> {
        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        val source = JSONObject(raw)
        return source.keys().asSequence().associateWith { id ->
            val item = source.getJSONObject(id)
            ComposerDraft(text = item.optString("text"))
        }.filterValues { it.text.isNotBlank() }
    }

    fun save(context: Context, drafts: Map<String, ComposerDraft>) {
        val source = JSONObject()
        drafts.forEach { (id, draft) ->
            if (draft.text.isNotBlank()) {
                source.put(id, JSONObject().put("text", draft.text))
            }
        }
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, source.toString()).commit()) {
            "本机草稿保存失败，请检查存储空间"
        }
    }
}
