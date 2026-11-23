package io.github.sy220284.codexandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEnvironmentConnectionPolicyTest {
    @Test fun acceptsEncryptedRemoteServiceAndExplicitLoopbackOnly() {
        assertTrue(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("wss://host.example/exec"))
        assertTrue(ExecutionEnvironmentConnectionPolicy.supportsEndpoint(" wss://host.example:443/exec "))
        assertTrue(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("ws://127.0.0.1:8080/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("ws://host.example/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("ws://localhost:8080/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("ws://127.0.0.1/exec"))
    }

    @Test fun rejectsIncompleteUnsafeOrMalformedConnectionStrings() {
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint(""))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("wss://"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("wss://host.example:0/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("wss://host.example:65536/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("wss://name:password@host.example/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("wss://host.example/exec#fragment"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("https://host.example/exec"))
        assertFalse(ExecutionEnvironmentConnectionPolicy.supportsEndpoint("  wss://host with spaces/exec"))
    }
}
