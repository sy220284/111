package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpServiceAuthPolicyTest {
    private fun service(name: String, status: String) = JSONObject()
        .put("name", name).put("authStatus", status)

    @Test fun hostedAppsUseChatGptIdentityEvenWhenBackendReportsNotLoggedIn() {
        for (status in listOf("notLoggedIn", "unknown", "unsupported", "oAuth")) {
            val server = service("codex_apps", status)
            val ui = McpServiceAuthPolicy.presentation(server)
            assertEquals(McpAuthAction.ChatGptAccount, ui.action)
            assertEquals("查看账号", ui.actionLabel)
            try {
                McpServiceAuthPolicy.oauthLoginParams(server, null)
                fail("Hosted apps must never start dynamic MCP registration")
            } catch (_: IllegalArgumentException) {
                // The centralized request contract rejects the wrong login path.
            }
        }
    }

    @Test fun onlyExplicitlyLoggedOutOAuthServersCanOpenBrowserAuthorization() {
        assertEquals(McpAuthAction.OAuthLogin,
            McpServiceAuthPolicy.presentation(service("vendor", "notLoggedIn")).action)
        for (status in listOf("oAuth", "bearerToken", "unknown", "unsupported", "")) {
            assertFalse(McpServiceAuthPolicy.presentation(service("vendor", status)).action ==
                McpAuthAction.OAuthLogin)
        }
        assertEquals("已授权",
            McpServiceAuthPolicy.presentation(service("vendor", "oAuth")).state)
        assertEquals("已配置令牌",
            McpServiceAuthPolicy.presentation(service("vendor", "bearerToken")).state)
        assertEquals(McpAuthAction.EngineConfiguration,
            McpServiceAuthPolicy.presentation(service("vendor", "unsupported")).action)
    }

    @Test fun oauthCarriesExactEnvironmentAndThreadScope() {
        val scoped = service("vendor", "notLoggedIn")
            .put("environmentId", "remote-1")
        val params = McpServiceAuthPolicy.oauthLoginParams(scoped, "thread-1")
        assertEquals("vendor", params.getString("name"))
        assertEquals("remote-1", params.getString("environmentId"))
        assertEquals("thread-1", params.getString("threadId"))
        val global = McpServiceAuthPolicy.oauthLoginParams(
            service("global", "notLoggedIn").put("environmentId", JSONObject.NULL), null)
        assertFalse(global.has("environmentId"))
        assertFalse(global.has("threadId"))
    }

    @Test fun loginCompletionOnlyMutatesTheMatchingAttempt() {
        val started = JSONObject().put("loginId", "current")
        assertTrue(McpServiceAuthPolicy.matchesCompletion(
            started, JSONObject().put("loginId", "current").put("success", true)))
        assertFalse(McpServiceAuthPolicy.matchesCompletion(
            started, JSONObject().put("loginId", "older").put("success", false)))
        assertFalse(McpServiceAuthPolicy.matchesCompletion(
            null, JSONObject().put("loginId", "current")))
    }
}
