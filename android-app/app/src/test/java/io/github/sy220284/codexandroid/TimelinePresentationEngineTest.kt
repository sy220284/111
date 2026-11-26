package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class TimelinePresentationEngineTest {
    @Test fun activityBudgetPreservesAllRowsAndRunningSteps() {
        val items = (0 until 120).map { i -> TimelineItem("step-$i", "commandExecution", "step",
            if (i == 2 || i == 70) "running" else "completed", turnId = "turn") }
        val rows = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(items), true, "turn", false)
        assertEquals(items.map { it.id }, rows.flatMap { it.group }.map { it.id })
        assertTrue(rows.all { it.group.size <= 12 })
        val visible = rows.flatMap { it.liveVisibleIds.orEmpty() }.toSet()
        assertEquals(setOf("step-2", "step-70", "step-115", "step-116", "step-117", "step-118", "step-119"), visible)
    }

    @Test fun offscreenPublicationRejectsReplacedSnapshot() {
        val timeline = ConversationLiveTimeline()
        val before = timeline.snapshot("a")
        val first = listOf(TimelineItem("id", "agentMessage", "one"))
        assertTrue(timeline.publish("a", before, first, setOf("a")))
        assertFalse(timeline.publish("a", before, emptyList(), setOf("a")))
        assertSame(first, timeline.snapshot("a"))
    }
}
