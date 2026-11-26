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

    fun event(threadId: String, method: String, params: JSONObject, activeThreads: Set<String>) {
        val updated = TimelineEventReducer.reduce(method, params, sessions.remove(threadId).orEmpty())
        sessions[threadId] = updated
        val inactive = sessions.keys.filter { it !in activeThreads }
        inactive.dropLast(8).forEach { sessions.remove(it) }
    }

    fun restore(threadId: String, saved: List<TimelineItem>): List<TimelineItem> =
        merge(saved, sessions.remove(threadId).orEmpty())

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
                if (phase == selected.phase) selected else selected.copy(phase = phase,
                    evidence = if (selected.hasEvidence) JSONObject(selected.evidence).put("phase", phase).toString() else "")
            } + live.filterNot { it.id in existing }
        }
    }
}
