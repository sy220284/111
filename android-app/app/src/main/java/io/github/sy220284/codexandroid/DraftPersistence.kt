package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONObject

/** Locally persisted unsent drafts; text is never sent anywhere by this store. */
internal object DraftPersistence {
    private const val STORE = "codex-unsent-drafts"
    private const val KEY = "drafts"

    fun decode(raw: String): Map<String, ComposerDraft> {
        val source = JSONObject(raw)
        val all = source.keys().asSequence().map { id ->
            val item = source.getJSONObject(id)
            id to ComposerDraft(text = item.optString("text"))
        }.filter { it.second.text.isNotBlank() }.toList()
        return all.toMap()
    }

    fun encode(drafts: Map<String, ComposerDraft>): String {
        val source = JSONObject()
        drafts.forEach { (id, draft) ->
            if (draft.text.isNotBlank()) {
                source.put(id, JSONObject().put("text", draft.text))
            }
        }
        return source.toString()
    }

    fun load(context: Context): Map<String, ComposerDraft> {
        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return decode(raw)
    }

    fun save(context: Context, drafts: Map<String, ComposerDraft>) {
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(drafts)).commit()) {
            "本机草稿保存失败，请检查存储空间"
        }
    }
}
