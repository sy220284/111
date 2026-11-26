package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Canonical ascending pages, including deferred output; never export only the visible page. */
internal class ThreadCopyHistory(private val request: suspend (String, JSONObject) -> JSONObject) {
    suspend fun read(threadId: String): JSONObject {
        require(threadId.isNotBlank()) { "缺少会话标识" }
        val turns = linkedMapOf<String, JSONArray>()
        val seenCursors = hashSetOf<String>()
        val seenItems = hashSetOf<Pair<String, String>>()
        var cursor: String? = null
        do {
            val params = JSONObject().put("threadId", threadId).put("sortDirection", "asc").put("limit", 100)
            cursor?.let { params.put("cursor", it) }
            val page = request("thread/items/list", params)
            val data = page.getJSONArray("data")
            for (index in 0 until data.length()) {
                var entry = data.getJSONObject(index)
                val turn = entry.getString("turnId")
                val id = entry.getJSONObject("item").getString("id")
                check(turn.isNotBlank() && id.isNotBlank()) { "历史缺少条目标识" }
                check(seenItems.add(turn to id)) { "历史条目重复，请重新读取" }
                if (entry.optBoolean("outputDeferred")) {
                    val detail = request("thread/items/read", JSONObject().put("threadId", threadId)
                        .put("turnId", turn).put("itemIds", JSONArray().put(id))).getJSONArray("data")
                    check(detail.length() == 1) { "历史输出读取不完整" }
                    entry = detail.getJSONObject(0)
                    check(entry.getString("turnId") == turn && entry.getJSONObject("item").getString("id") == id &&
                        !entry.optBoolean("outputDeferred")) { "历史输出归属不一致或未读取完整" }
                }
                turns.getOrPut(turn) { JSONArray() }.put(entry.getJSONObject("item"))
            }
            cursor = historyCursor(page)
            if (cursor != null) check(seenCursors.add(cursor)) { "历史分页重复，请重新读取" }
        } while (cursor != null)
        val outcomes = HistoryTurnOutcomes(threadId, request).load(turns.keys)
        return JSONObject().put("thread", JSONObject().put("turns", JSONArray().apply {
            turns.forEach { (id, items) ->
                val turn = outcomes[id]
                put(JSONObject().put("id", id).put("items", items)
                    .put("status", turn?.optString("status") ?: "unverified")
                    .put("error", turn?.opt("error") ?: JSONObject.NULL))
            }
        }))
    }
}
