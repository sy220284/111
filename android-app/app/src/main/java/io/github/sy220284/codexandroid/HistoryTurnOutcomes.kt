package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Session-scoped cursor for native turn metadata. Item completion never proves turn success. */
internal class HistoryTurnOutcomes(
    val threadId: String,
    private val request: suspend (String, JSONObject) -> JSONObject
) {
    private val turns = mutableMapOf<String, JSONObject>()
    private val seenCursors = hashSetOf<String>()
    private var cursor: String? = null
    private var exhausted = false

    suspend fun reconcile(items: List<TimelineItem>): List<TimelineItem> {
        val wanted = items.map { it.turnId }.filter(String::isNotBlank).toSet()
        if (wanted.isEmpty()) return items
        while (!exhausted && wanted.any { it !in turns }) {
            val params = JSONObject().put("threadId", threadId).put("limit", 120)
                .put("sortDirection", "desc").put("itemsView", "notLoaded")
            cursor?.let { params.put("cursor", it) }
            val page = request("thread/turns/list", params)
            val data = page.getJSONArray("data")
            val next = historyCursor(page)
            check(next == null || next !in seenCursors) { "回合状态分页重复，请重新打开会话" }
            for (index in 0 until data.length()) {
                val turn = data.getJSONObject(index)
                val id = turn.getString("id")
                check(id.isNotBlank()) { "回合状态缺少标识" }
                check(turn.getString("status") in setOf("completed", "failed", "interrupted", "inProgress")) {
                    "运行核心返回未知回合状态"
                }
                turns[id] = turn
            }
            cursor = next
            if (next == null) exhausted = true else seenCursors.add(next)
        }
        // A newly started turn may enter the item page after the metadata cursor was
        // established. Live events own its lifecycle; do not synthesize success for it.
        return items.groupBy { it.turnId }.values.flatMap { group ->
            val turn = turns[group.first().turnId] ?: return@flatMap group
            val status = turn.getString("status")
            if (status == "inProgress") group
            else TimelineEventReducer.reduce("turn/completed",
                JSONObject().put("turn", JSONObject().put("id", turn.getString("id"))
                    .put("status", status).put("error", turn.opt("error") ?: JSONObject.NULL)), group)
        }
    }
}
