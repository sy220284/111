package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedAccountIdentityTest {
    private fun account(user: String, plan: String = "plus") = JSONObject().put("userId", user)
        .put("account", JSONObject().put("type", "chatgpt").put("planType", plan))
    private fun sessions(user: String, session: String, workspace: String) = JSONObject()
        .put("activeSessionId", session).put("sessions", JSONArray().put(JSONObject()
            .put("sessionId", session).put("isActive", true).put("userId", user)
            .put("selectedWorkspaceAccountId", workspace).put("workspaces", JSONArray().put(
                JSONObject().put("accountId", workspace)))))
    @Test fun durableKeysFollowIdentityNotPresentation() {
        val key = AuthenticatedAccountIdentity.parse(account("a"), sessions("a", "s", "w"))!!.cacheKey()
        assertEquals(key, AuthenticatedAccountIdentity.parse(account("a", "pro"), sessions("a", "s", "w"))!!.cacheKey())
        assertNotEquals(key, AuthenticatedAccountIdentity.parse(account("b"), sessions("b", "s", "w"))!!.cacheKey())
        assertNotEquals(key, AuthenticatedAccountIdentity.parse(account("a"), sessions("a", "s", "w2"))!!.cacheKey())
        assertNotEquals(key, AuthenticatedAccountIdentity.parse(account("a"), sessions("a", "s2", "w"))!!.cacheKey())
    }
    @Test fun ambiguousOrExternalAuthenticationCannotReusePersistentCache() {
        val api = JSONObject().put("account", JSONObject().put("type", "apiKey"))
        assertNull(AuthenticatedAccountIdentity.parse(api, JSONObject()))
        assertNull(AuthenticatedAccountIdentity.parse(account("a"), JSONObject()))
        assertTrue(runCatching { AuthenticatedAccountIdentity.parse(account("a"), sessions("b", "s", "w")) }.isFailure)
    }
}
