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
        val history = ThreadCopyHistory { method, params ->
            methods += method
            when {
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
        }.read("thread")
        val output = ThreadMarkdownExporter.render(history)
        assertTrue(output.indexOf("第一条") < output.indexOf("完整输出"))
        assertEquals(listOf("thread/items/list", "thread/items/list", "thread/items/read"), methods)
    }

    @Test fun repeatedCursorFailsInsteadOfExportingPartialHistory() = runBlocking {
        val result = runCatching {
            ThreadCopyHistory { _, _ -> JSONObject().put("data", JSONArray()).put("nextCursor", "same") }.read("thread")
        }
        assertTrue(result.isFailure)
    }
}
