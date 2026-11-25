package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ActionReplyPolicyTest {
    @Test fun agentActionPreservesExistingDraftAndDoesNotDuplicateRequest() {
        assertEquals("原有草稿\n\n请求配置", AgentActionDraftPolicy.append("原有草稿", "请求配置"))
        assertEquals("原有草稿\n\n请求配置", AgentActionDraftPolicy.append("原有草稿\n\n请求配置", "请求配置"))
        assertEquals("请求配置", AgentActionDraftPolicy.append("", "请求配置"))
        assertEquals("原有草稿", AgentActionDraftPolicy.append("原有草稿", ""))
    }

    @Test fun endpointDerivesNameOnlyFromValidatedAddress() {
        assertEquals("example.com", ProviderAddressPolicy.defaultName("https://example.com/v1"))
        assertEquals("", ProviderAddressPolicy.defaultName("https://user:password@example.com"))
        assertTrue(ProviderAddressPolicy.validCredentialReference("PROVIDER_KEY"))
        assertFalse(ProviderAddressPolicy.validCredentialReference("key-value"))
        assertEquals("example.com-443", ExecutionEnvironmentConnectionPolicy.defaultName("wss://example.com:443/exec"))
        assertEquals("", ExecutionEnvironmentConnectionPolicy.defaultName("ws://example.com/exec"))
    }
}
