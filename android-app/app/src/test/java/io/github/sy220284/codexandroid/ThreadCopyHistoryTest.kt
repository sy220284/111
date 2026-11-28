package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadCopyHistoryTest {
    private fun entry(id: String, text: String, deferred: Boolean = false) = JSONObject()
        .put("turnId", "turn").put("outputDeferred", deferred)
        .put("item", JSONObject().put("id", id).put("type", "agentMessage").put("text", text))

    @Test fun followsAllPagesAndReadsDeferredOutput() = runBlocking {
        val methods = mutableListOf<String>()
        val spool = java.io.File.createTempFile("history-test", ".spool")
        val output = StringBuilder()
        try {
        ThreadCopyHistory { method, params ->
            methods += method
            when {
                method == "thread/turns/list" -> JSONObject().put("data", JSONArray().put(
                    JSONObject().put("id", "turn").put("status", "interrupted"))).put("nextCursor", JSONObject.NULL)
                method == "thread/items/read" -> {
                    assertEquals("turn", params.getString("turnId"))
                    assertEquals("b", params.getJSONArray("itemIds").getString(0))
                    JSONObject().put("data", JSONArray().put(entry("b", "完整输出")))
                }
                !params.has("cursor") -> JSONObject().put("data", JSONArray().put(entry("a", "第一条")))
                    .put("nextCursor", "next")
                else -> {
                    assertEquals("next", params.getString("cursor"))
                    JSONObject().put("data", JSONArray().put(entry("b", "", true))).put("nextCursor", JSONObject.NULL)
                }
            }
        }.write("thread", spool, output)
        } finally { spool.delete() }
        assertTrue(output.indexOf("第一条") < output.indexOf("完整输出"))
        assertTrue(output.contains("任务已中断"))
        assertEquals(listOf("thread/items/list", "thread/items/list", "thread/items/read", "thread/turns/list"), methods)
    }

    @Test fun repeatedCursorFailsInsteadOfExportingPartialHistory() = runBlocking {
        val spool = java.io.File.createTempFile("history-test", ".spool")
        val result = try { runCatching {
            ThreadCopyHistory { _, _ -> JSONObject().put("data", JSONArray()).put("nextCursor", "same") }
                .write("thread", spool, StringBuilder())
        } } finally { spool.delete() }
        assertTrue(result.isFailure)
    }
}
