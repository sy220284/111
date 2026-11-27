package io.github.sy220284.codexandroid

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded, keyed execution on the owning scope. Only the collector calls
 * submit/barrier/drain; workers inherit its dispatcher (Main in production).
 * Heavy preparation can suspend on Default, but commits remain owner-confined.
 * Failures cancel the structured scope instead of silently dropping events. */
internal class ThreadEventScheduler<K : Any>(
    private val scope: CoroutineScope,
    parallelism: Int = 4,
    private val capacity: Int = 64
) {
    init { require(parallelism > 0 && capacity >= parallelism) }
    private val slots = Semaphore(parallelism)
    private val pending = ArrayDeque<Job>()
    private val last = mutableMapOf<K, Job>()

    suspend fun submit(key: K, action: suspend () -> Unit) {
        prune()
        while (pending.size >= capacity) {
            pending.first.join()
            prune()
        }
        val predecessor = last[key]
        val job = scope.launch {
            predecessor?.join()
            slots.withPermit { action() }
        }
        pending.addLast(job)
        last[key] = job
    }

    private fun prune() {
        val jobs = pending.iterator()
        while (jobs.hasNext()) if (jobs.next().isCompleted) jobs.remove()
        val keys = last.entries.iterator()
        while (keys.hasNext()) if (keys.next().value.isCompleted) keys.remove()
    }

    suspend fun drain() {
        for (job in pending) job.join()
        pending.clear()
        last.clear()
    }

    suspend fun barrier(action: suspend () -> Unit) {
        drain()
        action()
    }
}
