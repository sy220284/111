package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RealtimeStartContractTest {
    @Test fun startsWithServerVoiceAndNoRemovedOverride() {
        val params = RealtimeStartContract.params("thread-1")
        assertEquals(setOf("threadId", "outputModality", "transport"), params.keys().asSequence().toSet())
        assertEquals("thread-1", params.getString("threadId"))
        assertEquals("audio", params.getString("outputModality"))
        assertEquals("websocket", params.getJSONObject("transport").getString("type"))
        assertFalse(params.has("voice"))
    }
}
