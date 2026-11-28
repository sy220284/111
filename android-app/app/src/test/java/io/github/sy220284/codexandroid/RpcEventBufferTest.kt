package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RpcEventBufferTest {
    private fun event(n: Int) = JSONObject()
        .put("sequence", n).put("method", if (n % 2 == 0) "turn/completed"
            else "item/agentMessage/delta")

    @Test fun transientSaturationDrainsInOrderWithoutRetiringTheConnection() = runBlocking {
        val inbox = Channel<JSONObject>(1)
        RpcEventBuffer.enqueue(inbox, event(1))
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            RpcEventBuffer.enqueue(inbox, event(2))
        }
        assertFalse(next.isCompleted)
        assertEquals(1, inbox.receive().getInt("sequence"))
        withTimeout(5_000) { next.await() }
        assertEquals(2, inbox.receive().getInt("sequence"))
        inbox.close()
        Unit
    }

    @Test fun stalledConsumerFailsExplicitlyWithoutReplacingQueuedEvents() = runBlocking {
        val inbox = Channel<JSONObject>(1)
        RpcEventBuffer.enqueue(inbox, event(1))
        val failure = runCatching { RpcEventBuffer.enqueue(inbox, event(2), drainTimeoutMs = 1) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(1, inbox.receive().getInt("sequence"))
        assertTrue(inbox.tryReceive().isFailure)
        inbox.close()
        Unit
    }

    @Test fun fullBufferSuspendsRatherThanDiscardingCriticalEvents() = runBlocking {
        val stream = RpcEventBuffer.create()
        val firstEventConsumed = CompletableDeferred<Unit>()
        val releaseSlowConsumer = CompletableDeferred<Unit>()
        val observed = mutableListOf<Int>()
        val total = RpcEventBuffer.CAPACITY + 2
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.take(total).collect { message ->
                if (message.getInt("sequence") == 0) {
                    firstEventConsumed.complete(Unit)
                    releaseSlowConsumer.await()
                }
                observed += message.getInt("sequence")
            }
        }
        assertEquals(1, stream.subscriptionCount.value)
        stream.emit(event(0))
        // Ensure event 0 has left the buffer before asserting the exact 512
        // queued events. Scheduling of a SharedFlow consumer is asynchronous.
        withTimeout(5_000) { firstEventConsumed.await() }
        repeat(RpcEventBuffer.CAPACITY) { index ->
            assertTrue("Queue must hold exactly the configured buffer capacity",
                stream.tryEmit(event(index + 1)))
        }
        assertFalse("Full queue must not silently overwrite the oldest event",
            stream.tryEmit(event(RpcEventBuffer.CAPACITY + 1)))
        val waitingProducer = async(start = CoroutineStart.UNDISPATCHED) {
            stream.emit(event(RpcEventBuffer.CAPACITY + 1))
        }
        assertFalse("Producer must be suspended until consumer progresses",
            waitingProducer.isCompleted)
        releaseSlowConsumer.complete(Unit)
        withTimeout(15_000) {
            waitingProducer.await()
            consumer.join()
        }
        assertEquals((0 until total).toList(), observed)
    }

    @Test fun thousandsOfMixedNotificationsRetainOrderForFastAndSlowSubscribers() = runBlocking {
        val stream = RpcEventBuffer.create()
        val total = 4096
        val fast = mutableListOf<Int>()
        val slow = mutableListOf<Int>()
        val fastConsumer = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.take(total).collect { fast += it.getInt("sequence") }
        }
        val slowConsumer = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.take(total).collect {
                val index = it.getInt("sequence")
                slow += index
                if (index % 32 == 0) delay(1)
            }
        }
        assertEquals(2, stream.subscriptionCount.value)
        withTimeout(20_000) {
            repeat(total) { stream.emit(event(it)) }
            fastConsumer.join()
            slowConsumer.join()
        }
        val expected = (0 until total).toList()
        assertEquals(expected, fast)
        assertEquals(expected, slow)
    }
}
