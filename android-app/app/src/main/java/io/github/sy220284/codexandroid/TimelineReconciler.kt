package io.github.sy220284.codexandroid

/** Reconcile authoritative item IDs without text equality. */
internal object TimelineReconciler {
    fun position(items: List<TimelineItem>, serverId: String, kind: String): Int {
        val sameId = items.indexOfLast { it.id == serverId }
        if (sameId >= 0) return sameId
        if (kind != "userMessage") return -1
        return items.indexOfFirst {
            it.id.startsWith("pending-") && it.kind == "user" && it.status == "sending"
        }
    }
}
