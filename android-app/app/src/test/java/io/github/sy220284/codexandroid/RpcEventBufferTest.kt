package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RpcEventBufferTest {
    private fun event(n: Int) = JSONObject()
        .put("sequence", n).put("method", if (n % 2 == 0) "turn/completed"
            else "item/agentMessage/delta")

    @Test fun fullBufferSuspendsRatherThanDiscardingCriticalEvents() = runBlocking {
        val stream = RpcEventBuffer.create()
        val releaseSlowConsumer = CompletableDeferred<Unit>()
        val observed = mutableListOf<Int>()
        val total = RpcEventBuffer.CAPACITY + 2
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.take(total).collect { message ->
                if (message.getInt("sequence") == 0) releaseSlowConsumer.await()
                observed += message.getInt("sequence")
            }
        }
        assertEquals(1, stream.subscriptionCount.value)
        stream.emit(event(0))
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
