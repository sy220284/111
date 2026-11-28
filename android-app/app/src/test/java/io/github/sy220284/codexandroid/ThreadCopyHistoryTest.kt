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

    @Test fun interleavedTurnsAreStreamedInTurnOrderWithoutFetchingHiddenToolPayloads() = runBlocking {
        val spool = java.io.File.createTempFile("history-interleaved", ".spool")
        val output = StringBuilder()
        try {
            val a = entry("a", "first").put("turnId", "one")
            a.getJSONObject("item").put("phase", "partial_answer")
            val b = entry("b", "third").put("turnId", "two")
            val c = entry("c", "second").put("turnId", "one")
            c.getJSONObject("item").put("phase", "final_answer")
            val hidden = entry("hidden", "", true).put("turnId", "one")
            hidden.getJSONObject("item").put("type", "commandExecution")
            ThreadCopyHistory { method, _ ->
                when (method) {
                    "thread/items/list" -> JSONObject().put("data", JSONArray().put(a).put(b).put(hidden).put(c))
                    "thread/turns/list" -> JSONObject().put("data", JSONArray()
                        .put(JSONObject().put("id", "one").put("status", "completed"))
                        .put(JSONObject().put("id", "two").put("status", "completed")))
                    else -> error("不应读取隐藏的执行输出")
                }
            }.write("thread", spool, output)
            assertEquals("# Codex 会话记录\n\n## Codex\n\nfirstsecond\n\n## Codex\n\nthird\n", output.toString())
        } finally { spool.delete() }
    }

    @Test fun repeatedCursorFailsInsteadOfExportingPartialHistory() = runBlocking {
        val spool = java.io.File.createTempFile("history-test", ".spool")
        val result = try { runCatching {
            ThreadCopyHistory { _, _ -> JSONObject().put("data", JSONArray()).put("nextCursor", "same") }
                .write("thread", spool, StringBuilder())
        } } finally { spool.delete() }
        assertTrue(result.isFailure)
    }

    @Test fun exportsFiftyThousandItemsAcrossPagesWithoutLosingOrder() = runBlocking {
        val spool = java.io.File.createTempFile("history-pressure", ".spool")
        val output = StringBuilder()
        var read = 0
        try {
            ThreadCopyHistory { method, params ->
                when (method) {
                    "thread/items/list" -> {
                        val start = params.optString("cursor").toIntOrNull() ?: 0
                        val end = minOf(start + 100, 50_000)
                        val rows = JSONArray()
                        for (i in start until end) rows.put(entry("id-$i", "record-$i;").put("turnId", "turn-${i / 100}"))
                        JSONObject().put("data", rows).put("nextCursor", if (end < 50_000) end.toString() else JSONObject.NULL)
                    }
                    "thread/turns/list" -> {
                        val start = params.optString("cursor").toIntOrNull() ?: 0
                        val end = minOf(start + 100, 500)
                        val rows = JSONArray()
                        for (i in start until end) rows.put(JSONObject().put("id", "turn-$i").put("status", "completed"))
                        JSONObject().put("data", rows).put("nextCursor", if (end < 500) end.toString() else JSONObject.NULL)
                    }
                    else -> error("unexpected method $method")
                }
            }.write("thread", spool, output, onProgress = { read = it })
            assertEquals(50_000, read)
            var position = -1
            for (i in 0 until 50_000) {
                val next = output.indexOf("record-$i;", position + 1)
                assertTrue("missing or out of order: $i", next > position)
                position = next
            }
        } finally { spool.delete() }
    }
}
