package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class InteractionViewportPolicyTest {
    @Test fun olderPageEntryDoesNotOffsetLatestMessageIncorrectly() {
        assertEquals(-1, TimelineViewportPolicy.latestIndex(0, true))
        assertEquals(2, TimelineViewportPolicy.latestIndex(3, false))
        assertEquals(3, TimelineViewportPolicy.latestIndex(3, true))
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
