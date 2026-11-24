package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionListPolicyTest {
    private fun row(id: String, created: Long, recent: Long) = JSONObject()
        .put("id", id).put("createdAt", created).put("recencyAt", recent)
    @Test fun pinnedThenRecentAndDeduplicated() {
        val rows = listOf(row("old", 1, 100), row("new", 20, 20),
            row("pin", 2, 3), row("old", 1, 100), row("", 0, 0))
        assertEquals(listOf("pin", "old", "new"),
            SessionListPolicy.ordered(rows, setOf("pin")).map(SessionListPolicy::id))
        assertEquals("recency_at", SessionListPolicy.params(false).getString("sortKey"))
        assertNull(SessionListPolicy.cursor(JSONObject().put("nextCursor", JSONObject.NULL)))
        assertEquals("opaque-token", SessionListPolicy.cursor(JSONObject().put("nextCursor", "opaque-token")))
        assertEquals("opaque-token", SessionListPolicy.params(true, "opaque-token").getString("cursor"))
        assertTrue(SessionListPolicy.params(true).getBoolean("archived"))
    }
    @Test fun actualWaitingStateOverridesRunningAndReceiptsAreReal() {
        assertEquals(ThreadActivity.WaitingAnswer, SessionListPolicy.activity(row("a", 1, 1),
            ThreadActivity.WaitingAnswer, true, 0))
        assertEquals(ThreadActivity.WaitingApproval, SessionListPolicy.activity(row("a", 1, 1),
            ThreadActivity.Running, true, 1))
        assertFalse(SessionListPolicy.unread(null))
        assertTrue(SessionListPolicy.unread(JSONObject().put("firstUnread", JSONObject().put("type", "threadStart"))))
    }
}
