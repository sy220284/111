package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SafetyReplyPresentationTest {
    private fun raw(display: JSONObject) = JSONObject().put("type", "agentMessage").put("id", "a")
        .put("text", "受限原文").put("moderation", JSONObject().put("moderated", true).put("display", display))
    @Test fun allNativeDisplayModesAndUnknownVersion() {
        val show = raw(JSONObject().put("mode", "show").put("version", 1).put("trustedContacts", "check")
            .put("annotation", JSONObject().put("kind", "wellbeingHelpline").put("subject", "answer")))
        assertFalse(AgentReplyContract.restricted(show))
        assertTrue(SafetyReplyPresentation.parse(show).trustedContactsCheck)
        assertTrue(SafetyReplyPresentation.parse(show).helpline)
        val hide = raw(JSONObject().put("mode", "hide").put("version", 1)
            .put("replacement", JSONObject().put("kind", "wellbeingPromptHelpline")))
        assertTrue(AgentReplyContract.restricted(hide))
        assertTrue(SafetyReplyPresentation.parse(hide).helpline)
        assertFalse(AgentReplyContract.sanitize(hide).toString().contains("受限原文"))
        val cyber = raw(JSONObject().put("mode", "block").put("version", 1)
            .put("reason", JSONObject().put("kind", "cyber").put("trustedAccess", true)))
        assertEquals(SafetyReplyPresentation.Mode.Cyber, SafetyReplyPresentation.parse(cyber).mode)
        assertTrue(AgentReplyContract.restricted(cyber))
        assertTrue(AgentReplyContract.restricted(raw(JSONObject().put("mode", "show").put("version", 2)
            .put("trustedContacts", "skip"))))
        assertTrue(AgentReplyContract.restricted(raw(JSONObject().put("mode", "show").put("version", 1))))
    }
}
