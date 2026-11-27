package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RemoteFileDeliveryTest {
    @Test fun pathsStayWithinRemoteBinding() {
        val binding = RemoteExecutionBinding("work", "ws://127.0.0.1:8080", "/home/user/work")
        assertEquals("out/a.pdf", RemoteFileDelivery.selection(binding, "out/a.pdf").relative)
        assertEquals("a.pdf", RemoteFileDelivery.selection(binding, "/home/user/work/out/a.pdf").filename)
        for (path in listOf("/home/user/work2/a", "/etc/passwd", "../a", "out/../a", "out//a", "a\\b"))
            assertTrue(path, runCatching { RemoteFileDelivery.selection(binding, path) }.isFailure)
    }

    @Test fun durableIdentityUsesActualAccountSessionWorkspaceAndPath() {
        val binding = RemoteExecutionBinding("work", "ws://127.0.0.1:8080", "/home/user/work")
        val account = org.json.JSONObject().put("account", org.json.JSONObject().put("type", "chatgpt")).put("userId", "user-1")
        fun sessions(workspace: String = "workspace-1", user: String = "user-1") =
            org.json.JSONObject().put("activeSessionId", "session-1").put("sessions", org.json.JSONArray()
                .put(org.json.JSONObject().put("sessionId", "session-1").put("isActive", true).put("userId", user)
                    .put("selectedWorkspaceAccountId", workspace).put("workspaces", org.json.JSONArray()
                        .put(org.json.JSONObject().put("accountId", workspace)))))
        val key = RemoteDeliveryAccountIdentity.key(account, sessions(), "thread", binding, "out/a")
        assertNotNull(key)
        assertTrue(RemoteTransferCheckpoint.isDigest(key!!))
        assertEquals(key, RemoteDeliveryAccountIdentity.key(account, sessions(), "thread", binding, "out/a"))
        assertNotEquals(key, RemoteDeliveryAccountIdentity.key(account, sessions("workspace-2"), "thread", binding, "out/a"))
        assertNotEquals(key, RemoteDeliveryAccountIdentity.key(account, sessions(), "thread", binding, "out/b"))
        assertTrue(runCatching { RemoteDeliveryAccountIdentity.key(account, sessions(user = "other"), "thread", binding, "out/a") }.isFailure)
        assertNull(RemoteDeliveryAccountIdentity.key(org.json.JSONObject(), sessions(), "thread", binding, "out/a"))
    }
}
