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

    @Test fun directoryReceiptMustMatchTheRequestedOwnerAndNeverOpenLinks() {
        val binding = RemoteExecutionBinding("work", "ws://127.0.0.1:8080", "/home/user/work")
        fun row(name: String, kind: String) = org.json.JSONObject().put("name", name).put("kind", kind)
        fun reply(path: String, vararg entries: org.json.JSONObject) = org.json.JSONObject()
            .put("environmentId", "work").put("pathUri", path)
            .put("entries", org.json.JSONArray(entries.toList()))
        val result = RemoteDirectory.decode(binding, "", reply("file:///home/user/work",
            row("readme.txt", "file"), row("nested", "directory"), row("escape", "other")))
        assertEquals(listOf("nested", "escape", "readme.txt"), result.map { it.name })
        assertNull(result[1].relative)
        assertEquals("nested", result[0].relative)
        assertEquals("nested/file.txt", RemoteDirectory.decode(binding, "nested",
            reply("file:///home/user/work/nested", row("file.txt", "file"))).single().relative)
        for (bad in listOf(reply("file:///outside", row("a", "file")),
            reply("file:///home/user/work", row("../a", "file")),
            reply("file:///home/user/work", row("a", "file"), row("a", "directory")))) {
            assertTrue(runCatching { RemoteDirectory.decode(binding, "", bad) }.isFailure)
        }
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
