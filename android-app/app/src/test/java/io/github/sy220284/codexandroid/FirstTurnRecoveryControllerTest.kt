package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FirstTurnRecoveryControllerTest {
    private fun history(turns: Int): JSONObject =
        JSONObject().put("thread", JSONObject().put("turns",
            JSONArray().apply { repeat(turns) { put(JSONObject().put("id", "t-$it")) } }))

    @Test fun reclaimsOnlyVerifiedEmptyCurrentThread() = runBlocking {
        val calls = mutableListOf<String>()
        var reclaimed = false
        val controller = FirstTurnRecoveryController { method, _, _, _ ->
            calls += method
            if (method == "thread/read") history(0) else JSONObject()
        }
        val result = controller.reclaim("thread-1", { "thread-1" }, { null }) {
            reclaimed = true
        }
        assertEquals("；新建空会话已清理", result)
        assertEquals(listOf("thread/read", "thread/delete"), calls)
        assertTrue(reclaimed)
    }

    @Test fun keepsNonemptyOrNoLongerCurrentThreads() = runBlocking {
        for (turns in listOf(0, 1)) {
            val calls = mutableListOf<String>()
            val controller = FirstTurnRecoveryController { method, _, _, _ ->
                calls += method
                history(turns)
            }
            val activeId = if (turns == 0) "other-thread" else "thread-1"
            assertEquals("", controller.reclaim("thread-1", { activeId }, { null }) {
                fail("A noncurrent or nonempty thread must be retained")
            })
            assertEquals(listOf("thread/read"), calls)
        }
    }

    @Test fun keepsThreadIfTurnStartedBeforeReconciliation() = runBlocking {
        val controller = FirstTurnRecoveryController { method, _, _, _ ->
            assertEquals("thread/read", method)
            history(0)
        }
        assertEquals("", controller.reclaim("thread-1", { "thread-1" }, { "turn-active" }) {
            fail("Live turn must never be deleted")
        })
    }
    @Test fun rejectsDeletionWhenSelectionChangesWhileWaitingForWriter() = runBlocking {
        var active = "thread-1"
        var reclaimed = false
        val controller = FirstTurnRecoveryController { method, _, _, permit ->
            if (method == "thread/read") history(0) else {
                active = "thread-2"
                check(permit()) { "old selection" }
                JSONObject()
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.reclaim("thread-1", { active }, { null }) { reclaimed = true } }
        }
        assertFalse(reclaimed)
    }
}
