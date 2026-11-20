package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalProtocolTest {
    @Test fun managedHumanCommandCannotGrantSession() {
        val prompt = ServerPrompt(9, "item/commandExecution/requestApproval",
            JSONObject().put("kind", "humanCommand").put("command", "rm -rf *"))
        assertEquals("accept", ApprovalProtocol.result(prompt, "accept").getString("decision"))
        try {
            ApprovalProtocol.result(prompt, "acceptForSession")
            fail("managed approval cannot grant future executions")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun permissionGrantMatchesRequestAndScope() {
        val requested = JSONObject().put("network", JSONObject().put("enabled", true))
        val prompt = ServerPrompt(11, "item/permissions/requestApproval",
            JSONObject().put("permissions", requested))
        val accepted = ApprovalProtocol.result(prompt, "accept")
        assertEquals("turn", accepted.getString("scope"))
        assertTrue(accepted.getJSONObject("permissions").getJSONObject("network").getBoolean("enabled"))
        val declined = ApprovalProtocol.result(prompt, "decline")
        assertEquals(0, declined.getJSONObject("permissions").length())
    }

    @Test fun everyQuestionMustBeAnswered() {
        val questions = JSONArray().put(JSONObject().put("id", "scope"))
            .put(JSONObject().put("id", "name"))
        val prompt = ServerPrompt("req-2", "item/tool/requestUserInput",
            JSONObject().put("questions", questions))
        try {
            ApprovalProtocol.result(prompt, "submit", mapOf("scope" to "local"))
            fail("incomplete multi-question input must be rejected")
        } catch (_: IllegalArgumentException) {}
        val valid = ApprovalProtocol.result(prompt, "submit", mapOf("scope" to "local", "name" to "codex"))
        val map = valid.getJSONObject("answers")
        assertEquals("local", map.getJSONObject("scope").getJSONArray("answers").getString(0))
        assertEquals("codex", map.getJSONObject("name").getJSONArray("answers").getString(0))
    }

    @Test fun externalElicitationOnlySupportsExplicitDecline() {
        val prompt = ServerPrompt(18, "mcpServer/elicitation/request", JSONObject())
        assertEquals("decline", ApprovalProtocol.result(prompt, "decline").getString("action"))
        try {
            ApprovalProtocol.result(prompt, "accept")
            fail("acceptance requires typed form data")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun unknownServerRequestRequiresOwnAdapter() {
        try {
            ApprovalProtocol.result(ServerPrompt(2, "item/tool/call", JSONObject()), "accept")
            fail("unsupported method must not be interpreted as approved")
        } catch (_: IllegalArgumentException) {}
    }
}
