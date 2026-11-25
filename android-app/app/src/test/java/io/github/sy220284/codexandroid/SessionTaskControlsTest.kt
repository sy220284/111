package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionTaskControlsTest {
    @Test fun sortUsesSelectedTimestampAndRetainsPins() {
        val older = JSONObject("""{"id":"old","createdAt":1,"updatedAt":20,"recencyAt":30}""")
        val newer = JSONObject("""{"id":"new","createdAt":10,"updatedAt":15,"recencyAt":40}""")
        val rows = listOf(older, newer)
        assertEquals(listOf(newer, older), SessionListPolicy.ordered(rows, emptySet(), SessionSort.Created))
        assertEquals(listOf(older, newer), SessionListPolicy.ordered(rows, emptySet(), SessionSort.Updated))
        assertEquals(listOf(newer, older), SessionListPolicy.ordered(rows, emptySet(), SessionSort.Recent))
        assertEquals(listOf(older, newer), SessionListPolicy.ordered(rows, setOf("old"), SessionSort.Created))
    }
    @Test fun activeAndAttentionAreDistinct() {
        assertTrue(SessionTaskFilter.Active.matches(ThreadActivity.WaitingAnswer))
        assertTrue(SessionTaskFilter.Waiting.matches(ThreadActivity.WaitingApproval))
        assertFalse(SessionTaskFilter.Running.matches(ThreadActivity.WaitingApproval))
        assertFalse(SessionTaskFilter.Active.matches(ThreadActivity.Failed))
        assertTrue(SessionTaskFilter.Failed.matches(ThreadActivity.Failed))
    }
}
