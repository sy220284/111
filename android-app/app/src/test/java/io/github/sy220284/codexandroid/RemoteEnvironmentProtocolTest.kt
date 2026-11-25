package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteEnvironmentProtocolTest {
    private val binding = RemoteExecutionBinding("work", "wss://example.org/exec", "/home/work")

    @Test fun repeatRegistrationDoesNotReplaceExecutorAndNewCoreRegistersAgain() = runBlocking {
        val protocol = RemoteEnvironmentProtocol()
        var state = "unknown"
        var adds = 0
        val request: suspend (String, JSONObject) -> JSONObject = { method, params ->
            when (method) {
                "environment/status" -> JSONObject().put("status", state)
                "environment/add" -> {
                    assertEquals(binding.endpoint, params.getString("execServerUrl"))
                    adds++; state = "pending"; JSONObject()
                }
                else -> error(method)
            }
        }
        protocol.register(1, binding.id, binding.endpoint, request) {}
        protocol.register(1, binding.id, binding.endpoint, request) {}
        assertEquals(1, adds)
        assertTrue(runCatching { protocol.register(1, binding.id, "wss://other.example/exec", request) {} }.isFailure)
        assertEquals(1, adds)
        state = "unknown"
        protocol.register(2, binding.id, binding.endpoint, request) {}
        assertEquals(2, adds)
    }

    @Test fun refusesUnownedRegisteredEnvironment() = runBlocking {
        val calls = mutableListOf<String>()
        val result = runCatching {
            RemoteEnvironmentProtocol().register(1, binding.id, binding.endpoint, { method, _ ->
                calls += method
                JSONObject().put("status", "ready")
            }) {}
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("environment/status"), calls)
    }

    @Test fun idleForkIsReboundAndRunningMismatchCannotBeChanged() = runBlocking {
        var selected = JSONObject()
        val calls = mutableListOf<String>()
        val request: suspend (String, JSONObject) -> JSONObject = { method, params ->
            calls += method
            when (method) {
                "thread/read" -> JSONObject().put("thread", selected)
                "thread/settings/update" -> { selected = params; JSONObject() }
                else -> error(method)
            }
        }
        RemoteEnvironmentProtocol.confirmThread("fork", binding, false, request)
        assertTrue(binding.matches(selected))
        assertEquals(listOf("thread/read", "thread/settings/update", "thread/read"), calls)
        selected = JSONObject()
        calls.clear()
        assertTrue(runCatching { RemoteEnvironmentProtocol.confirmThread("fork", binding, true, request) }.isFailure)
        assertEquals(listOf("thread/read"), calls)
    }
}
