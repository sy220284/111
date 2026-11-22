package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalDecisionPolicyTest {
    private fun prompt(method: String, kind: String = "") =
        ServerPrompt(12, method, JSONObject().put("kind", kind))

    @Test fun commandAndFileApprovalActionsHaveCorrectWireCodes() {
        for (method in listOf("item/commandExecution/requestApproval",
            "item/fileChange/requestApproval")) {
            val request = prompt(method)
            for ((action, expected) in mapOf(
                ApprovalAction.Decline to "decline",
                ApprovalAction.Cancel to "cancel",
                ApprovalAction.AllowOnce to "accept",
                ApprovalAction.AllowSession to "acceptForSession")) {
                val decision = ApprovalDecisionPolicy.resolve(request, action)
                assertEquals(expected, decision)
                assertEquals(expected, ApprovalProtocol.result(request, decision)
                    .getString("decision"))
            }
        }
    }

    @Test fun humanCommandCannotBecomeSessionWideOrTurnPermission() {
        val request = prompt("item/commandExecution/requestApproval", "humanCommand")
        try {
            ApprovalDecisionPolicy.resolve(request, ApprovalAction.AllowSession)
            fail("Session-wide human command must be rejected")
        } catch (_: IllegalArgumentException) {}
        try {
            ApprovalDecisionPolicy.resolve(request, ApprovalAction.GrantTurn)
            fail("The UI must not grant unrequested permissions")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun permissionScopeOnlyAllowsExplicitTurnGrant() {
        val request = ServerPrompt(17, "item/permissions/requestApproval",
            JSONObject().put("permissions", JSONObject().put("network", true)))
        assertEquals("accept", ApprovalDecisionPolicy.resolve(request, ApprovalAction.GrantTurn))
        assertEquals("turn", ApprovalProtocol.result(request,
            ApprovalDecisionPolicy.resolve(request, ApprovalAction.GrantTurn))
            .getString("scope"))
        for (invalid in listOf(ApprovalAction.AllowOnce,
            ApprovalAction.AllowSession, ApprovalAction.Cancel)) {
            try {
                ApprovalDecisionPolicy.resolve(request, invalid)
                fail("Permission expansion must not use $invalid")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun mcpRequiresTypedFormForConsentAndQuestionsHaveNoDenyWireCode() {
        val mcp = prompt("mcpServer/elicitation/request")
        assertEquals("decline", ApprovalDecisionPolicy.resolve(mcp, ApprovalAction.Decline))
        assertEquals("cancel", ApprovalDecisionPolicy.resolve(mcp, ApprovalAction.Cancel))
        for (invalid in listOf(ApprovalAction.AllowOnce,
            ApprovalAction.AllowSession, ApprovalAction.GrantTurn)) {
            try {
                ApprovalDecisionPolicy.resolve(mcp, invalid)
                fail("MCP consent requires validated form")
            } catch (_: IllegalArgumentException) {}
        }
        try {
            ApprovalDecisionPolicy.resolve(
                prompt("item/tool/requestUserInput"), ApprovalAction.Decline)
            fail("User input response has only an answers object")
        } catch (_: IllegalArgumentException) {}
    }
}
