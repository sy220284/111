package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** One presentation boundary for assistant metadata, aggregation and all user-visible text. */
internal object AgentReplyContract {
    private val metadataKeys = listOf("phase", "moderation", "delivery", "questions", "memoryCitation",
        "startedAtMs", "completedAtMs")
    fun restricted(raw: JSONObject?): Boolean = SafetyReplyPresentation.parse(raw).restricted
    fun visibleText(raw: JSONObject): String =
        if (raw.optString("type") == "agentMessage" && restricted(raw))
            SafetyReplyPresentation.parse(raw).replacement
        else if (raw.isNull("text")) "" else raw.optString("text")

    fun sanitize(raw: JSONObject): JSONObject {
        if (raw.optString("type") != "agentMessage" || !restricted(raw)) return raw
        // Keep identity and display instructions, but never retain suppressed text, questions or citations
        // in an Android-visible projection, local timeline cache, clipboard or exported record.
        return JSONObject().apply {
            for (key in listOf("id", "type", "phase", "moderation", "delivery", "startedAtMs", "completedAtMs"))
                if (!raw.isNull(key)) put(key, raw.get(key))
            put("text", visibleText(raw))
        }
    }

    fun merge(previous: TimelineItem?, incoming: TimelineItem, keepText: Boolean = false): TimelineItem {
        if (incoming.kind != "agentMessage" || previous == null || previous.kind != incoming.kind) return incoming
        val prior = previous.metadata()
        val next = JSONObject((incoming.metadata() ?: JSONObject().put("type", "agentMessage").put("id", incoming.id)).toString())
        for (key in metadataKeys) if (next.isNull(key) && prior != null && !prior.isNull(key)) putMissing(next, prior, key)
        // A late incomplete snapshot cannot erase a previously applied restriction.
        if (restricted(prior) && !restricted(next) && (keepText || incoming.status != "completed") && prior != null)
            next.put("moderation", prior.get("moderation"))
        val text = if (keepText) previous.text else incoming.text
        next.put("text", text)
        val safe = sanitize(next)
        val phase = if (safe.isNull("phase")) incoming.phase.ifBlank { previous.phase } else safe.optString("phase")
        return incoming.copy(text = visibleText(safe), phase = phase, evidence = safe.toString())
    }
    private fun putMissing(target: JSONObject, source: JSONObject, key: String) {
        target.put(key, source.get(key))
    }

    fun aggregate(items: List<TimelineItem>): TimelineItem {
        val last = items.last()
        val records = JSONArray()
        val text = items.joinToString("") { item ->
            val raw = item.metadata()
            if (raw != null) {
                val safe = sanitize(raw)
                val attributes = JSONObject().put("id", item.id).put("phase", item.phase)
                for (key in metadataKeys) if (!safe.isNull(key)) attributes.put(key, safe.get(key))
                records.put(attributes)
                if (restricted(safe)) visibleText(safe) else item.text
            } else {
                records.put(JSONObject().put("id", item.id).put("phase", item.phase))
                item.text
            }
        }
        val evidence = JSONObject().put("type", "agentMessage").put("id", last.id)
            .put("phase", last.phase).put("text", text).put("fragments", records)
        return last.copy(text = text, evidence = evidence.toString())
    }
}
