package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ProtocolDirectionPolicyTest {
    @Test fun hostRequestsCanNeverBeSentAsAndroidInitiatedMethods() {
        val hostOnly = listOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request",
            "item/permissions/requestApproval",
            "item/tool/call",
            "account/chatgptAuthTokens/refresh",
            "attestation/generate"
        )
        hostOnly.forEach { method ->
            try {
                ProtocolDirectionPolicy.requireClientSend(method)
                fail("Host-only request was incorrectly accepted: $method")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty().contains(method))
            }
        }
    }

    @Test fun legalClientRequestsAndInitializeNotificationRemainAvailable() {
        listOf("initialize", "initialized", "thread/start", "thread/read",
            "turn/start", "command/exec", "model/list").forEach { method ->
            ProtocolDirectionPolicy.requireClientSend(method)
        }
    }
}
