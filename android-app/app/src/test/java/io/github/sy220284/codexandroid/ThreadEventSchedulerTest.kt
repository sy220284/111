package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ThreadEventSchedulerTest {
    @Test fun independentThreadsProgressAndBarriersDrain() = runTest {
        coroutineScope {
            val queue = ThreadEventScheduler<String>(this)
            val gate = CompletableDeferred<Unit>()
            val independent = CompletableDeferred<Unit>()
            val observed = mutableListOf<String>()
            queue.submit("a") { gate.await(); observed.add("a0") }
            queue.submit("a") { observed.add("a1") }
            queue.submit("b") { observed.add("b0"); independent.complete(Unit) }
            independent.await()
            assertEquals(listOf("b0"), observed)
            gate.complete(Unit)
            queue.barrier { assertEquals(listOf("b0", "a0", "a1"), observed); observed.add("barrier") }
            queue.submit("a") { assertEquals("barrier", observed.last()) }
            queue.drain()
        }
    }

    @Test fun tenThousandEventsKeepThreadOrderAndBoundParallelism() = runTest {
        coroutineScope {
            val queue = ThreadEventScheduler<String>(this, 4, 64)
            val expected = IntArray(13)
            var concurrent = 0
            var maxConcurrent = 0
            repeat(10000) { n ->
                val k = n % expected.size
                queue.submit(k.toString()) {
                    concurrent++
                    maxConcurrent = maxOf(maxConcurrent, concurrent)
                    yield()
                    assertEquals(n / expected.size, expected[k]++)
                    concurrent--
                }
            }
            queue.barrier { assertEquals(10000, expected.sum()) }
            assertTrue(maxConcurrent in 2..4)
        }
    }
}
