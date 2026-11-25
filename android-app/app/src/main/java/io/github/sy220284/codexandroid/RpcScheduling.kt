package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred

internal enum class RpcPriority { Control, Interactive, Background }

/** Classify by actual wire method. Background reads cannot consume control capacity. */
internal object RpcScheduling {
    fun priority(method: String): RpcPriority = when (method) {
        "turn/interrupt", "command/exec/terminate", "command/exec/write", "command/exec/resize",
        "thread/realtime/stop", "account/login/cancel", "initialized", "initialize" -> RpcPriority.Control
        "account/read", "model/list", "skills/list", "app/list", "app/installed", "plugin/list", "plugin/installed",
        "account/usage/read", "account/rateLimits/read", "thread/list", "mcpServerStatus/list" -> RpcPriority.Background
        else -> RpcPriority.Interactive
    }

    fun limit(priority: RpcPriority): Int = when (priority) {
        RpcPriority.Control -> 12
        RpcPriority.Interactive -> 32
        RpcPriority.Background -> 12
    }

    const val WRITE_TIMEOUT_MS = 15_000L
    const val RETIRE_TIMEOUT_MS = 5_000L
    const val EVENT_CAPACITY = 1024
}

/** A cancellable, bounded priority queue for the one physical writer.
 * One committed frame is indivisible; after eight priority grants, the oldest
 * waiter gets a turn so background work cannot starve. */
internal class RpcWriteScheduler {
    private class Ticket(val priority: RpcPriority) {
        val ready = CompletableDeferred<Unit>()
    }
    private val queued = mutableListOf<Ticket>()
    private var current: Ticket? = null
    private var priorityGrants = 0

    suspend fun <T> run(priority: RpcPriority, block: suspend () -> T): T {
        val ticket = Ticket(priority)
        synchronized(this) {
            val limit = if (priority == RpcPriority.Control) 80 else 64
            check(queued.size < limit) { "运行核心请求排队已满，请稍后重试" }
            queued.add(ticket)
            grant()
        }
        try {
            ticket.ready.await()
            return block()
        } finally {
            synchronized(this) {
                if (current === ticket) {
                    current = null
                    grant()
                } else queued.remove(ticket)
            }
        }
    }

    private fun grant() {
        if (current != null || queued.isEmpty()) return
        val preferred = queued.indices.minByOrNull { queued[it].priority.ordinal } ?: return
        val selected = if (priorityGrants >= 8) 0 else preferred
        priorityGrants = if (selected == 0) 0 else priorityGrants + 1
        current = queued.removeAt(selected)
        current?.ready?.complete(Unit)
    }
}
