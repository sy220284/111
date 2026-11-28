package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Deletes only an explicitly rejected, still-current, server-confirmed empty thread. */
internal class FirstTurnRecoveryController(
    private val rpc: suspend (String, JSONObject, Long?, () -> Boolean) -> JSONObject
) {
    suspend fun reclaim(
        createdId: String,
        currentThread: () -> String?,
        currentTurn: () -> String?,
        onReclaimed: () -> Unit
    ): String {
        val checked = rpc("thread/read", JSONObject().put("threadId", createdId)
            .put("includeTurns", true), 20_000L) { true }
        val turns = checked.optJSONObject("thread")?.optJSONArray("turns")
        val permit = { currentThread() == createdId && currentTurn().isNullOrBlank() }
        if (turns == null || turns.length() != 0 || !permit()) return ""
        rpc("thread/delete", JSONObject().put("threadId", createdId), 45_000L, permit)
        onReclaimed()
        return "；新建空会话已清理"
    }
}
