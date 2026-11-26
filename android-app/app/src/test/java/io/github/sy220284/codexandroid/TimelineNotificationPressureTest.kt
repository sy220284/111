package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimelineNotificationPressureTest {
    private fun event(method: String, params: JSONObject, epoch: Long = 1L) =
        JSONObject().put("method", method).put("params", params).put(CORE_SESSION_FIELD, epoch)
    private fun delta(id: String, value: String, epoch: Long = 1L) = event("item/agentMessage/delta",
        JSONObject().put("threadId", "thread").put("turnId", "turn").put("itemId", id).put("delta", value), epoch)
    private fun lifecycle(method: String, id: String, text: String) = event(method,
        JSONObject().put("threadId", "thread").put("turnId", "turn")
            .put("item", JSONObject().put("id", id).put("type", "agentMessage").put("text", text)))

    @Test fun burstPreservesAllTextAndLifecycleBarriers() = runTest {
        val count = 5000
        val messages = buildList {
            add(lifecycle("item/started", "a", ""))
            repeat(count) { add(delta("a", "字")) }
            add(lifecycle("item/completed", "a", "字".repeat(count)))
            add(lifecycle("item/started", "b", ""))
            repeat(256) { add(delta("b", "x")) }
        }
        val expected = messages.fold(emptyList<TimelineItem>()) { state, wire ->
            TimelineEventReducer.reduce(wire.getString("method"), wire.getJSONObject("params"), state)
        }
        var actual: List<TimelineItem> = emptyList()
        var dispatched = 0
        val barriers = mutableListOf<String>()
        collectTimelineNotifications(messages.asFlow()) { notification ->
            dispatched++
            if (notification.mergedDelta == null) barriers += notification.method
            actual = TimelineEventReducer.reduce(notification.method, notification.params, actual, notification.mergedDelta)
        }
        assertEquals(expected, actual)
        assertEquals("字".repeat(count), actual.first { it.id == "a" }.text)
        assertEquals("x".repeat(256), actual.first { it.id == "b" }.text)
        assertEquals(listOf("item/started", "item/completed", "item/started"), barriers)
        assertTrue(dispatched < messages.size)
    }

    @Test fun interleavedItemsDoNotPayOneDisplayFramePerNotification() = runTest {
        val messages = (0 until 5000).map { delta("item-${it % 2}", it.toString()) }
        val observed = mutableListOf<Pair<String, String>>()
        collectTimelineNotifications(messages.asFlow()) {
            observed += it.params.getString("itemId") to it.delta
        }
        assertEquals(messages.map { it.getJSONObject("params").let { p -> p.getString("itemId") to p.getString("delta") } }, observed)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun batchNeverCrossesEpochOrItem() = runTest {
        val messages = listOf(delta("a", "a"), delta("a", "b"), delta("a", "c", 2L),
            delta("b", "d", 2L))
        val observed = mutableListOf<Triple<Long, String, String>>()
        collectTimelineNotifications(messages.asFlow()) {
            observed += Triple(it.wire.getLong(CORE_SESSION_FIELD), it.params.getString("itemId"), it.delta)
        }
        assertEquals(listOf(Triple(1L, "a", "ab"), Triple(2L, "a", "c"), Triple(2L, "b", "d")), observed)
    }

    @Test fun independentThreadProgressBeforeLifecycleBarrier() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val progress = mutableListOf<String>()
        val a = delta("a", "x")
        val b = delta("b", "y").also { it.getJSONObject("params").put("threadId", "other") }
        val finish = lifecycle("item/completed", "a", "x")
        val messages = flow {
            emit(a)
            entered.await()
            emit(b)
            emit(finish)
        }
        collectTimelineNotifications(messages) { notification ->
            when (notification.params.optString("itemId")) {
                "a" -> { entered.complete(Unit); release.await(); progress += "a" }
                "b" -> { progress += "b"; release.complete(Unit) }
                else -> progress += "finish"
            }
        }
        assertEquals(listOf("b", "a", "finish"), progress)
    }

    @Test fun epochSwitchDrainsOldThreadBeforeNewThread() = runTest {
        val first = delta("a", "x", 1)
        val second = delta("b", "y", 2).also { it.getJSONObject("params").put("threadId", "other") }
        val order = mutableListOf<Long>()
        collectTimelineNotifications(listOf(first, second).asFlow()) {
            if (it.wire.getLong(CORE_SESSION_FIELD) == 1L) kotlinx.coroutines.yield()
            order += it.wire.getLong(CORE_SESSION_FIELD)
        }
        assertEquals(listOf(1L, 2L), order)
    }
}
