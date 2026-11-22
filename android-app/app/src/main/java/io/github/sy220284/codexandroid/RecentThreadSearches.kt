package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONArray

/** Recent title/body queries stay only on this device and are capped independently. */
internal object RecentThreadSearches {
    private const val STORE = "codex-recent-thread-queries"
    private const val LIMIT = 5

    fun promote(previous: List<String>, term: String): List<String> {
        val normalized = term.trim()
        return (if (normalized.isBlank()) previous else listOf(normalized) + previous)
            .map(String::trim).filter(String::isNotEmpty).distinct().take(LIMIT)
    }

    fun encode(terms: List<String>): String =
        JSONArray(promote(terms, "")).toString()

    fun decode(raw: String): List<String> {
        val array = JSONArray(raw)
        return promote((0 until array.length()).map { array.getString(it) }, "")
    }

    private fun key(fullText: Boolean) = if (fullText) "body" else "title"

    fun load(context: Context, fullText: Boolean): List<String> {
        val raw = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(key(fullText), null) ?: return emptyList()
        return decode(raw)
    }

    fun clear(context: Context, fullText: Boolean): List<String> {
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().remove(key(fullText)).commit()) {
            "无法清除本机搜索记录"
        }
        return emptyList()
    }

    fun record(context: Context, fullText: Boolean, query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isBlank()) return load(context, fullText)
        val updated = promote(load(context, fullText), normalized)
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(key(fullText), encode(updated)).commit()) {
            "无法保存本地搜索记录"
        }
        return updated
    }
}
