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

    @Test fun incrementalActivityIndexMatchesCompleteRecordsAfterUpdates() {
        val random = kotlin.random.Random(116)
        val items = (0 until 5000).map { i ->
            TimelineItem("step-$i", "commandExecution", "", if (i % 127 == 0) "running" else "completed", turnId = "turn")
        }.toMutableList()
        var grouping = TimelineGrouping.from(items)
        repeat(300) { n ->
            val pos = random.nextInt(items.size)
            val item = items[pos].copy(text = "delta-$n", status = if (random.nextInt(5) == 0) "running" else "completed")
            items[pos] = item
            grouping = grouping.replace(pos, item)
            val expected = (items.filter(TimelineActivityPolicy::running) +
                items.filterNot(TimelineActivityPolicy::running).takeLast(5)).map { it.id }.toSet()
            val actual = TimelineActivityVisibility.byGroup(grouping.groups).values.flatMap { it.values }.flatten().toSet()
            assertEquals(expected, actual)
            assertEquals(items, grouping.groups.flatten())
        }
        val indexed = ProgressiveReplyProjection.rows(grouping.groups, true, "turn", false)
        val plain = ProgressiveReplyProjection.rows(grouping.groups.map { it.toList() }, true, "turn", false)
        assertEquals(plain, indexed)
        assertEquals(items, indexed.flatMap { it.group })
        assertTrue(indexed.all { it.group.size <= TimelineActivityPolicy.GROUP_PAGE_SIZE })
    }

    @Test fun turnBoundaryTracksAppendAndHistoryIdentity() {
        val a = TimelineItem("a", "commandExecution", "", "running", turnId = "t")
        val b = TimelineItem("b", "agentMessage", "", "streaming", turnId = "t")
        val first = TimelineGrouping.from(listOf(a))
        val second = first.append(b)
        assertEquals(0, second.lastTurnStart)
        assertTrue(first.sameIdentityPrefix(second, 1))
        val third = second.append(b.copy(id = "c", turnId = "next"))
        assertEquals(2, third.lastTurnStart)
        val fourth = third.append(b.copy(id = "d", turnId = ""))
        assertEquals(3, fourth.lastTurnStart)
    }
}
