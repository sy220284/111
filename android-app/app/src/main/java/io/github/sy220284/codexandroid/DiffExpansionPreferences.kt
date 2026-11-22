package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Preserve per-thread diff expansion choices across screen navigation and process restarts. */
internal object DiffExpansionPreferences {
    private const val STORE = "codex-diff-expansion"

    fun default(fileCount: Int): Set<Int> =
        if (fileCount == 1) setOf(0) else emptySet()

    fun encode(diff: String, expanded: Set<Int>): String = JSONObject()
        .put("hash", diff.hashCode())
        .put("length", diff.length)
        .put("expanded", JSONArray(expanded.sorted()))
        .toString()

    fun decode(raw: String?, diff: String, fileCount: Int): Set<Int> {
        if (raw == null) return default(fileCount)
        val saved = JSONObject(raw)
        if (saved.optInt("hash") != diff.hashCode() ||
            saved.optInt("length") != diff.length) return default(fileCount)
        val entries = saved.getJSONArray("expanded")
        return (0 until entries.length()).map { entries.getInt(it) }
            .filter { it in 0 until fileCount }.toSet()
    }

    fun load(context: Context, threadId: String?, diff: String, fileCount: Int): Set<Int> {
        if (threadId.isNullOrBlank()) return default(fileCount)
        return decode(
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString("thread:$threadId", null),
            diff, fileCount
        )
    }

    fun save(context: Context, threadId: String?, diff: String, expanded: Set<Int>) {
        if (threadId.isNullOrBlank()) return
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString("thread:$threadId", encode(diff, expanded)).commit()) {
            "无法保存差异展开偏好"
        }
    }
}
