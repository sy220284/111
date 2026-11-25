package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RpcSchedulingTest {
    @Test fun controlOvertakesQueuedBackgroundWithoutInterruptingCurrentFrame() = runBlocking {
        val scheduler = RpcWriteScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val current = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(RpcPriority.Interactive) { order += "current"; release.await() }
        }
        val bulk = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(RpcPriority.Background) { order += "bulk" }
        }
        val control = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(RpcPriority.Control) { order += "stop" }
        }
        assertEquals(listOf("current"), order)
        release.complete(Unit)
        current.join(); bulk.join(); control.join()
        assertEquals(listOf("current", "stop", "bulk"), order)
    }

    @Test fun cancellationReleasesQueuedTicketAndBackgroundCannotStarve() = runBlocking {
        val scheduler = RpcWriteScheduler()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val current = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(RpcPriority.Interactive) { release.await() }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(RpcPriority.Control) { fail("Cancelled write ran") }
        }
        cancelled.cancelAndJoin()
        val jobs = listOf(launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.run(RpcPriority.Background) { order += "bulk" }
        }) + (1..10).map { n ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run(RpcPriority.Control) { order += "control$n" }
            }
        }
        release.complete(Unit)
        current.join(); jobs.forEach { it.join() }
        assertEquals(11, order.size)
        assertTrue(order.indexOf("bulk") <= 8)
        assertEquals(RpcPriority.Control, RpcScheduling.priority("turn/interrupt"))
        assertEquals(RpcPriority.Background, RpcScheduling.priority("model/list"))
    }
}
