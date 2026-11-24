package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatSetupPolicyTest {
    private fun account(required: Boolean) = JSONObject().put("account", JSONObject.NULL)
        .put("requiresOpenaiAuth", required)
    private fun modelList(count: Int, cursor: Any = JSONObject.NULL) =
        JSONObject().put("data", JSONArray().apply { repeat(count) { put(JSONObject()) } })
            .put("nextCursor", cursor)

    @Test fun onlyExplicitRequiredAuthorizationPromptsLogin() {
        assertEquals(AccountSetupState.Unknown, ChatSetupPolicy.accountState(null))
        assertEquals(AccountSetupState.Unknown, ChatSetupPolicy.accountState(JSONObject()))
        assertEquals(AccountSetupState.Unknown, ChatSetupPolicy.accountState(
            JSONObject().put("account", JSONObject.NULL).put("requiresOpenaiAuth", "true")))
        assertEquals(AccountSetupState.Required, ChatSetupPolicy.accountState(account(true)))
        assertEquals(AccountSetupState.Optional, ChatSetupPolicy.accountState(account(false)))
        assertEquals(ChatSetupHint.Authorize, ChatSetupPolicy.hint(true, account(true), modelList(0)))
        assertNull(ChatSetupPolicy.hint(false, account(true), modelList(0)))
        assertNull(ChatSetupPolicy.hint(true, account(false), modelList(1)))
    }

    @Test fun actualAccountAndCompleteModelCatalogDetermineGuidance() {
        val signedIn = account(true).put("account", JSONObject().put("type", "apiKey"))
        assertEquals(AccountSetupState.Authorized, ChatSetupPolicy.accountState(signedIn))
        assertNull(ChatSetupPolicy.hint(true, signedIn, modelList(1)))
        assertNull(ChatSetupPolicy.hint(true, signedIn, null))
        assertNull(ChatSetupPolicy.hint(true, signedIn, modelList(0, "next-page")))
        assertNull(ChatSetupPolicy.hint(true, signedIn, JSONObject().put("data", JSONArray())))
        assertEquals(ChatSetupHint.Models, ChatSetupPolicy.hint(true, signedIn, modelList(0)))
        assertEquals(ChatSetupHint.Models, ChatSetupPolicy.hint(true, account(false), modelList(0)))
    }
}
