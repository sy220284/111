package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AsyncReplyContractTest {
    @Test fun nativeQuestionIdAndReplyEnvelopeRoundTrip() {
        val raw = JSONObject().put("id", "message").put("type", "agentMessage").put("text", "")
            .put("delivery", "async").put("questions", JSONArray().put(JSONObject().put("title", "选择\n方案")
                .put("options", JSONArray().put("原生").put("网页"))))
        val item = TimelineEventReducer.project(raw, "turn", "completed")
        val question = AsyncReplyContract.questions(item).single()
        assertEquals("[\"request_user_input_async\",\"message\",0]", question.id)
        val encoded = AsyncReplyContract.encode(question, "原生")
        assertTrue(encoded.startsWith("<send_user_message_question_reply>\n"))
        assertEquals(setOf(question.id), AsyncReplyContract.answeredIds(encoded))
        assertEquals("回答：选择 方案\n原生", AsyncReplyContract.visibleUserText(encoded))
        assertEquals(listOf(question), AsyncReplyContract.questions(AgentReplyContract.aggregate(listOf(item))))
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", encoded))
        val user = TimelineEventReducer.project(JSONObject().put("id", "u").put("type", "userMessage")
            .put("content", input), "turn", "completed")
        assertEquals(setOf(question.id), AsyncReplyContract.answeredFromTimeline(listOf(user)))
        assertEquals(setOf(question.id), AsyncReplyContract.answeredFromQueue(JSONObject().put("data",
            JSONArray().put(JSONObject().put("input", input)))))
        assertNotEquals(AsyncReplyContract.key("thread-a", question), AsyncReplyContract.key("thread-b", question))
    }
}
