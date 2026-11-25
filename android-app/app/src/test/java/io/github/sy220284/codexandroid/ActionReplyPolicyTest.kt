package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ActionReplyPolicyTest {
    @Test fun agentActionNeverCombinesWithExistingDraft() {
        assertTrue(AgentActionDraftPolicy.canPrepare(null, "请求配置"))
        assertTrue(AgentActionDraftPolicy.canPrepare(ComposerDraft(), "请求配置"))
        assertFalse(AgentActionDraftPolicy.canPrepare(ComposerDraft(text = "原有草稿"), "请求配置"))
        assertFalse(AgentActionDraftPolicy.canPrepare(ComposerDraft(images = listOf("image.png")), "请求配置"))
        assertFalse(AgentActionDraftPolicy.canPrepare(ComposerDraft(), ""))
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
