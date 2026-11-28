package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** Stable, server-confirmed identity. Display names, plans and credential types
 * cannot identify an account. External authentication has no durable key. */
internal data class AuthenticatedAccountIdentity(val user: String, val session: String, val workspace: String) {
    fun cacheKey(): String = MessageDigest.getInstance("SHA-256").digest(
        JSONArray().put("111-session-index-v2").put(user).put(session).put(workspace).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun parse(account: JSONObject, sessions: JSONObject): AuthenticatedAccountIdentity? {
            if (account.optJSONObject("account")?.optString("type") != "chatgpt") return null
            val user = (account.opt("userId") as? String)?.takeIf(String::isNotBlank) ?: return null
            val active = (sessions.opt("activeSessionId") as? String)?.takeIf(String::isNotBlank) ?: return null
            val records = sessions.getJSONArray("sessions")
            val selected = (0 until records.length()).map { records.getJSONObject(it) }
                .filter { it.optString("sessionId") == active || it.optBoolean("isActive") }
            check(selected.size == 1 && selected.single().optString("sessionId") == active &&
                selected.single().opt("userId") == user) { "账号会话身份不一致，请重新连接" }
            val session = selected.single()
            val workspace = (session.opt("selectedWorkspaceAccountId") as? String)
                ?.takeIf(String::isNotBlank) ?: return null
            val workspaces = session.getJSONArray("workspaces")
            check((0 until workspaces.length()).count {
                workspaces.getJSONObject(it).opt("accountId") == workspace
            } == 1) { "账号工作区身份不一致，请重新连接" }
            return AuthenticatedAccountIdentity(user, active, workspace)
        }
    }
}
