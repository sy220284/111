package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class InteractionViewportPolicyTest {
    @Test fun olderPageEntryDoesNotOffsetLatestMessageIncorrectly() {
        assertEquals(-1, TimelineViewportPolicy.latestIndex(0))
        assertEquals(0, TimelineViewportPolicy.latestIndex(3))
        assertEquals(0, TimelineViewportPolicy.latestIndex(4))
        assertEquals(3, TimelineViewportPolicy.measuredCount(3, false))
        assertEquals(4, TimelineViewportPolicy.measuredCount(3, true))
        assertEquals(0, TimelineViewportPolicy.measuredCount(0, true))
        // Chronological content coordinates are independent of the older-page control.
        for (size in 1..128) for (recovery in 0..1) {
            val indices = (0 until size).map { TimelineViewportPolicy.physicalIndex(it, size, recovery) }
            assertEquals((recovery until recovery + size).reversed().toList(), indices)
            assertEquals(recovery, indices.last())
            assertEquals(-1, TimelineViewportPolicy.physicalIndex(-1, size, recovery))
            assertEquals(-1, TimelineViewportPolicy.physicalIndex(size, size, recovery))
        }
    }

    @Test fun streamingGrowthFollowsTailWithoutCountingAsNewStep() {
        val old = TimelineItem("a", "agentMessage", "第一段", "inProgress")
        val updated = old.copy(text = "第一段第二段")
        val change = TimelineViewportPolicy.change(1, old, 1, updated)
        assertTrue(change.changed)
        assertEquals(0, change.newSteps)
        val next = TimelineViewportPolicy.change(1, updated, 2, TimelineItem("b", "plan", "完成"))
        assertEquals(1, next.newSteps)
        assertFalse(TimelineViewportPolicy.change(1, updated, 5, updated).changed)
    }

    @Test fun initialTailFollowWaitsForMeasurementAndConfirmsActualEnd() {
        with(TimelineViewportPolicy) {
            assertEquals(TimelineViewportPolicy.FollowAction.Await, followAction(33, 0, 0, "tail", null, true))
            assertEquals(TimelineViewportPolicy.FollowAction.RequestTarget, followAction(33, 33, 640, "tail", "history", true))
            assertEquals(TimelineViewportPolicy.FollowAction.RequestTarget, followAction(33, 33, 640, "tail", "tail", true))
            assertEquals(TimelineViewportPolicy.FollowAction.AtEnd, followAction(33, 33, 640, "tail", "tail", false))
            // A growing row remains bottom-anchored with zero scroll; a new row
            // must take over index zero only when its actual layout exists.
            assertEquals(TimelineViewportPolicy.FollowAction.AtEnd, followAction(33, 33, 640, "tail", "tail", false))
            assertEquals(TimelineViewportPolicy.FollowAction.Await, followAction(34, 33, 640, "new", "tail", false))
            assertEquals(TimelineViewportPolicy.FollowAction.RequestTarget, followAction(34, 34, 640, "new", "tail", true))
            assertEquals(TimelineViewportPolicy.FollowAction.AtEnd, followAction(34, 34, 640, "new", "new", false))
            assertEquals(TimelineViewportPolicy.FollowAction.RequestTarget, followAction(33, 33, 640, "replacement", "tail", false))
            assertEquals(TimelineViewportPolicy.FollowAction.Await, followAction(0, 0, 640, null, null, false))
        }
    }

    @Test fun readerFindsMatchesAcrossChunksAndHighlightsBothParts() {
        val text = "x".repeat(3199) + "AB" + " abc"
        val hits = FullTextSearchPolicy.find(text, "ab")
        assertEquals(listOf(3199, 3202), hits)
        assertEquals(listOf(FullTextSearchPolicy.Slice(3199, 3200, true)),
            FullTextSearchPolicy.slices(0, 3200, 2, hits, 0))
        assertEquals(listOf(FullTextSearchPolicy.Slice(0, 1, true),
            FullTextSearchPolicy.Slice(2, 4, false)),
            FullTextSearchPolicy.slices(1, text.length - 3200, 2, hits, 0))
        assertTrue(FullTextSearchPolicy.find(text, "").isEmpty())
    }
}
