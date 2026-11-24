package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnswerVersionsTest {
    private fun row(id: String) = JSONObject().put("id", id).put("preview", id)
    @Test fun versionsStayInOneLogicalConversationAndKeepTheirNativeIdentities() {
        val state = AnswerVersionState().add("root", "first", "retry").bind("retry", "second")
            .select("retry").add("retry", "second", "third").bind("third", "third-turn").select("third")
        assertEquals(listOf("root", "retry", "third"), state.members("third"))
        assertEquals(2, state.choice("third", "third-turn")!!.index)
        assertEquals(listOf("third", "explicit-fork"), state.rows(listOf(row("root"), row("retry"), row("third"), row("explicit-fork"))).map(SessionListPolicy::id))
        assertEquals(state, AnswerVersionState.decode(state.encode()))
        assertEquals("root", state.choice("third", "third-turn")!!.versions.first().threadId)
    }
    @Test fun earlierVersionUsesItsOwnTurnSlot() {
        val state = AnswerVersionState().add("root", "a", "retry-a").bind("retry-a", "a2")
            .add("retry-a", "b", "retry-b").bind("retry-b", "b2")
        assertEquals(2, state.choice("retry-b", "a2")!!.versions.size)
        assertEquals(2, state.choice("retry-b", "b2")!!.versions.size)
        assertNull(state.choice("unrelated", "a2"))
    }
}
