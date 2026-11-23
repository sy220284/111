package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RpcWireRequestTest {
    @Test fun unitTypedMcpReloadOmitsParamsInsteadOfSendingEmptyObject() {
        val wire = RpcWireRequest.envelope("config/mcpServer/reload", 41L, null)
        assertEquals("config/mcpServer/reload", wire.getString("method"))
        assertEquals(41L, wire.getLong("id"))
        assertFalse(wire.has("params"))
    }

    @Test fun normalEmptyObjectIsPreservedForMethodsThatRequireIt() {
        val wire = RpcWireRequest.envelope("account/read", 42L, JSONObject())
        assertTrue(wire.has("params"))
        assertEquals(0, wire.getJSONObject("params").length())
    }

    @Test fun populatedParamsRemainUnchanged() {
        val args = JSONObject().put("threadId", "thread-123")
        val wire = RpcWireRequest.envelope("thread/read", 43L, args)
        assertEquals("thread-123", wire.getJSONObject("params").getString("threadId"))
    }
}
