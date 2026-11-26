package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentReplyContractTest {
    private fun raw(text: String = "正文") = JSONObject().put("id", "a").put("type", "agentMessage")
        .put("phase", "partial_answer").put("text", text)
    private fun restricted(raw: JSONObject) = raw.put("moderation", JSONObject().put("moderated", true)
        .put("display", JSONObject().put("mode", "block").put("version", 1)
            .put("reason", JSONObject().put("kind", "protection"))))

    @Test fun restrictionAppliesToLiveHistoryAggregateAndExport() {
        val blocked = restricted(raw("受限原文")).put("questions", JSONArray().put(JSONObject().put("title", "受限问题")))
        val params = JSONObject().put("turnId", "t").put("item", blocked)
        val started = TimelineEventReducer.reduce("item/started", params, emptyList())
        val delta = JSONObject().put("turnId", "t").put("itemId", "a").put("delta", "受限增量")
        assertEquals(started, TimelineEventReducer.reduce("item/agentMessage/delta", delta, started))
        val completed = TimelineEventReducer.reduce("item/completed", params, started).single()
        assertFalse(completed.evidence.contains("受限原文"))
        assertFalse(completed.evidence.contains("受限问题"))
        assertFalse(AgentReplyContract.aggregate(listOf(completed)).evidence.contains("受限原文"))
        val history = JSONObject().put("thread", JSONObject().put("turns", JSONArray()
            .put(JSONObject().put("items", JSONArray().put(blocked)))))
        assertFalse(ThreadMarkdownExporter.render(history).contains("受限原文"))
    }

    @Test fun omittedAttributesAndLateRestrictionArePreserved() {
        val item = raw().put("delivery", "async").put("questions", JSONArray().put(JSONObject().put("title", "问题")))
        val prior = TimelineEventReducer.project(item, "t", "completed")
        val later = TimelineEventReducer.project(raw(), "t", "completed")
        val merged = AgentReplyContract.merge(prior, later)
        assertEquals("async", merged.metadata()?.getString("delivery"))
        assertEquals("问题", merged.metadata()?.getJSONArray("questions")?.getJSONObject(0)?.getString("title"))
        val aggregate = AgentReplyContract.aggregate(listOf(merged))
        assertEquals("a", aggregate.metadata()?.getJSONArray("fragments")?.getJSONObject(0)?.getString("id"))
        val hidden = AgentReplyContract.merge(merged, TimelineEventReducer.project(restricted(raw()), "t", "completed"), true)
        assertFalse(hidden.text.contains("正文"))
        assertFalse(hidden.evidence.contains("问题"))
    }
}
