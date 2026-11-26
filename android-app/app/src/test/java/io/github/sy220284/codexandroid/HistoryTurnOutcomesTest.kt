package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryTurnOutcomesTest {
    @Test fun nativeTurnMetadataDistinguishesItemCompletionFromTurnSuccessAcrossPages() = runBlocking {
        val cursors = mutableListOf<String>()
        fun turn(id: String, status: String) = JSONObject().put("id", id).put("status", status)
        val reader = HistoryTurnOutcomes("thread") { method, params ->
            assertEquals("thread/turns/list", method)
            assertEquals("thread", params.getString("threadId"))
            assertEquals("notLoaded", params.getString("itemsView"))
            val cursor = params.optString("cursor")
            cursors += cursor
            when (cursor) {
                "" -> JSONObject().put("data", JSONArray().put(turn("new", "completed"))).put("nextCursor", "older")
                "older" -> JSONObject().put("data", JSONArray().put(turn("old", "interrupted"))).put("nextCursor", JSONObject.NULL)
                else -> error("重复请求")
            }
        }
        fun answer(id: String) = TimelineItem(id, "agentMessage", "已有内容", "completed",
            turnId = id, phase = "partial_answer")
        assertEquals(answer("new"), reader.reconcile(listOf(answer("new"))).first())
        val older = reader.reconcile(listOf(answer("old")))
        assertEquals(listOf("", "older"), cursors)
        assertEquals("已有内容", older.first().text)
        assertEquals("interrupted", older.last().status)
        assertEquals("turn-error:old", older.last().id)
        val rows = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(older), false, null, false)
        assertEquals("任务未成功完成，已保留收到的回答", rows.first().note)
        assertEquals(older, reader.reconcile(listOf(answer("old"))))
        assertEquals(2, cursors.size)
    }
}
