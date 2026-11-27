package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Keeps offscreen execution projections, reconciled by native item identity, never replayed over saved text. */
internal class ConversationLiveTimeline {
    private val sessions = linkedMapOf<String, List<TimelineItem>>()

    fun capture(threadId: String, turnId: String?, items: List<TimelineItem>) {
        if (!turnId.isNullOrBlank()) {
            val current = items.filter { it.turnId == turnId }
            sessions[threadId] = merge(sessions[threadId].orEmpty(), current)
        }
    }

    fun event(threadId: String, method: String, params: JSONObject, activeThreads: Set<String>,
        deltaText: String? = null) {
        val before = snapshot(threadId)
        val updated = TimelineEventReducer.reduce(method, params, before.orEmpty(), deltaText)
        check(publish(threadId, before, updated, activeThreads))
    }

    fun restore(threadId: String, saved: List<TimelineItem>): List<TimelineItem> =
        merge(saved, sessions.remove(threadId).orEmpty())

    fun snapshot(threadId: String): List<TimelineItem>? = sessions[threadId]

    fun publish(threadId: String, before: List<TimelineItem>?, next: List<TimelineItem>,
        activeThreads: Set<String>): Boolean {
        if (sessions[threadId] !== before) return false
        sessions.remove(threadId)
        sessions[threadId] = next
        val inactive = sessions.keys.filter { it !in activeThreads }
        inactive.dropLast(8).forEach { sessions.remove(it) }
        return true
    }

    fun remove(threadId: String) { sessions.remove(threadId) }

    companion object {
        private val terminal = setOf("completed", "failed", "blocked", "stopped", "declined", "interrupted", "cancelled", "incomplete")
        fun merge(saved: List<TimelineItem>, live: List<TimelineItem>): List<TimelineItem> {
            val byId = live.associateBy { it.id }
            val existing = saved.mapTo(hashSetOf()) { it.id }
            return saved.map { prior ->
                val next = byId[prior.id] ?: return@map prior
                val selected = if (prior.status in terminal) prior
                else if (next.status in terminal) next
                else {
                    val field = when (next.kind) {
                        "commandExecution" -> "aggregatedOutput"
                        "fileChange" -> "output"
                        "mcpToolCall" -> "progress"
                        else -> null
                    }
                    fun content(item: TimelineItem) = if (field == null) item.text
                        else if (!item.hasEvidence) "" else JSONObject(item.evidence).optString(field)
                    val previous = content(prior)
                    val incoming = content(next)
                    when {
                        incoming.startsWith(previous) -> next
                        previous.startsWith(incoming) -> prior
                        else -> next
                    }
                }
                // The longest/terminal snapshot owns text. An explicit phase from the other
                // snapshot still belongs to this same native item and must not be discarded.
                val phase = if (selected.kind != "agentMessage") selected.phase
                    else if (prior.status in terminal && next.status !in terminal) prior.phase.ifBlank { next.phase }
                    else next.phase.ifBlank { prior.phase }
                if (selected.kind == "agentMessage" && (prior.hasEvidence || next.hasEvidence)) {
                    val other = if (selected === prior) next else prior
                    val merged = AgentReplyContract.merge(other, selected, keepText = false)
                    // Retain a restriction even if a stale selected snapshot lacked its metadata.
                    if (AgentReplyContract.restricted(other.metadata()) && !AgentReplyContract.restricted(merged.metadata()))
                        AgentReplyContract.merge(merged, other.copy(status = selected.status), keepText = true)
                    else merged.copy(phase = phase, evidence = if (merged.hasEvidence)
                        JSONObject(merged.evidence).put("phase", phase).toString() else "")
                } else if (phase == selected.phase) selected else selected.copy(phase = phase,
                    evidence = if (selected.hasEvidence) JSONObject(selected.evidence).put("phase", phase).toString() else "")
            } + live.filterNot { it.id in existing || (it.id.startsWith("turn-unverified:") &&
                TimelineEventReducer.terminalOutcome(saved, it.turnId) != null) }
        }
    }
}
