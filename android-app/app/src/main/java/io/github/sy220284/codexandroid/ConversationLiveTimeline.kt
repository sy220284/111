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
        private val terminal = setOf("completed", "failed", "declined", "interrupted", "cancelled")
        fun merge(saved: List<TimelineItem>, live: List<TimelineItem>): List<TimelineItem> {
            val byId = live.associateBy { it.id }
            val existing = saved.mapTo(hashSetOf()) { it.id }
            return saved.map { prior ->
                val next = byId[prior.id] ?: return@map prior
                when {
                    prior.status in terminal -> prior
                    next.status in terminal -> next
                    next.text.startsWith(prior.text) -> next
                    prior.text.startsWith(next.text) -> prior
                    else -> next
                }
            } + live.filterNot { it.id in existing }
        }
    }
}
