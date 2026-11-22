package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcEnvelopeRouterTest {
    @Test fun responsesRouteToTheirAwaitingRequest() {
        assertEquals(RpcEnvelopeKind.Response,
            RpcEnvelopeRouter.classify(JSONObject().put("id", 42)
                .put("result", JSONObject().put("ok", true))))
        assertEquals(RpcEnvelopeKind.Event,
            RpcEnvelopeRouter.classify(JSONObject().put("method", "turn/completed")))
    }

    @Test fun malformedResponseIdsCannotDisappearAsUnrelatedEvents() {
        val invalid = listOf(
            JSONObject().put("id", "wrong-identifier").put("result", JSONObject()),
            JSONObject().put("id", JSONObject.NULL).put("result", JSONObject()),
            JSONObject().put("result", JSONObject()),
            JSONObject().put("error", JSONObject().put("code", -32600)
                .put("message", "missing id"))
        )
        invalid.forEach { response ->
            assertEquals(RpcEnvelopeKind.MalformedResponse,
                RpcEnvelopeRouter.classify(response))
        }
        assertEquals(RpcEnvelopeKind.Response,
            RpcEnvelopeRouter.classify(JSONObject().put("id", 4)))
    }

    @Test fun knownApprovalAndUserInputAreSupportedHostRequests() {
        for (method in listOf(
            "item/commandExecution/requestApproval",
            "item/permissions/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request"
        )) {
            assertEquals(RpcEnvelopeKind.HostRequest,
                RpcEnvelopeRouter.classify(JSONObject().put("id", "approval")
                    .put("method", method)))
        }
    }

    @Test fun everyOfficialHostRequestHasAnExplicitPolicy() {
        val expected = setOf(
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request",
            "item/tool/call",
            "account/chatgptAuthTokens/refresh",
            "attestation/generate"
        )
        assertEquals(expected, RpcEnvelopeRouter.officialHostRequests)
        for (method in RpcEnvelopeRouter.gatedHostRequests) {
            assertEquals(RpcEnvelopeKind.UnsupportedHostRequest,
                RpcEnvelopeRouter.classify(JSONObject().put("id", 7)
                    .put("method", method)))
            org.junit.Assert.assertTrue(RpcEnvelopeRouter.unsupportedReason(method).isNotBlank())
        }
    }

    @Test fun unknownHostMethodReceivesExplicitUnsupportedResponse() {
        assertEquals(RpcEnvelopeKind.UnsupportedHostRequest,
            RpcEnvelopeRouter.classify(JSONObject().put("id", 7)
                .put("method", "newUnexpected/serverRequest")))
    }

    @Test fun resolvedPromptNotificationIsRecognized() {
        assertEquals(RpcEnvelopeKind.RequestResolved,
            RpcEnvelopeRouter.classify(JSONObject()
                .put("method", "serverRequest/resolved")
                .put("params", JSONObject().put("requestId", 1))))
    }
}
