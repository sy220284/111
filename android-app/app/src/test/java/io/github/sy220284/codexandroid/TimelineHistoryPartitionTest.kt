package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class TimelineHistoryPartitionTest {
    @Test fun historicalProjectionRetainsIdentityWhileActiveTextChanges() {
        val engine = TimelinePresentationEngine()
        val historical = (0 until 300).map { i ->
            TimelineItem("history-$i", "agentMessage", "历史 $i", "completed", turnId = "turn-$i")
        }
        val tail = TimelineItem("live", "agentMessage", "first", "streaming", turnId = "active")
        val initial = IndexedTimeline.from(historical + tail)
        fun frame(items: List<TimelineItem>, turn: String? = "active", running: Boolean = true) =
            ActiveConversationContext("/workspace", threadId = "thread", turnId = turn, running = running, timeline = items)
        val first = engine.project(frame(initial), false)
        assertEquals(300, first.history.rows.size)
        var timeline = initial
        repeat(100) { n ->
            timeline = timeline.put(tail.copy(text = "answer $n"))
            val next = engine.project(frame(timeline), false)
            assertSame(first.history, next.history)
            assertEquals(ProgressiveReplyProjection.rows(TimelineEventReducer.groups(timeline), true, "active", false), next.rows)
            assertEquals(timeline.last(), next.byId("live"))
            assertTrue(next.hasFormalTurn("turn-1"))
        }
        // Late native event invalidates exactly the cached historical snapshot.
        timeline = timeline.put(historical[17].copy(text = "修订历史"))
        val changed = engine.project(frame(timeline), false)
        assertNotSame(first.history, changed.history)
        assertEquals("修订历史", changed.byId("history-17")?.text)
        assertEquals(ProgressiveReplyProjection.rows(TimelineEventReducer.groups(timeline), true, "active", false), changed.rows)
        val switched = engine.project(frame(timeline, "turn-3"), true)
        assertTrue(switched.history.rows.isEmpty())
        assertEquals(ProgressiveReplyProjection.rows(TimelineEventReducer.groups(timeline), true, "turn-3", true), switched.rows)
    }

    @Test fun identityPrefixMatchesExactComparisonAcrossAppendsAndReplacements() {
        val base = PersistentSequence.from((0 until 1000).map { Any() })
        var current = base
        repeat(300) { current = current.append(Any()) }
        assertTrue(base.sameIdentityPrefix(current, base.size))
        val changed = current.replace(300, Any())
        assertTrue(base.sameIdentityPrefix(changed, 300))
        assertFalse(base.sameIdentityPrefix(changed, 301))
        assertTrue(current.sameIdentityPrefix(changed, 300))
        assertFalse(current.sameIdentityPrefix(changed, 301))
    }
}
