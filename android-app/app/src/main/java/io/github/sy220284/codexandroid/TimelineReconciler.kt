package io.github.sy220284.codexandroid

/** Reconcile authoritative item IDs without text equality. */
internal object TimelineReconciler {
    fun position(items: List<TimelineItem>, serverId: String, kind: String, clientId: String = ""): Int {
        val sameId = items.indexOfLast { it.id == serverId }
        if (sameId >= 0) return sameId
        if (kind != "userMessage" || clientId.isBlank()) return -1
        return items.indexOfFirst { it.id == "pending-$clientId" && it.kind == "user" }
    }
}
