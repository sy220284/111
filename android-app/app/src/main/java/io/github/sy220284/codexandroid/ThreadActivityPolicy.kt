package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Codex v2 per-thread status, independent of which thread is on screen. */
internal enum class ThreadActivity { Idle, Running, WaitingApproval, WaitingAnswer, Failed }

internal object ThreadActivityPolicy {
    private const val MAX_RECENT = 256

    fun parse(status: JSONObject?): ThreadActivity = when (status?.optString("type")) {
        "systemError" -> ThreadActivity.Failed
        "active" -> {
            val flags = status.optJSONArray("activeFlags")
            val values = (0 until (flags?.length() ?: 0)).map { flags?.optString(it).orEmpty() }
            when {
                "waitingOnApproval" in values -> ThreadActivity.WaitingApproval
                "waitingOnUserInput" in values -> ThreadActivity.WaitingAnswer
                else -> ThreadActivity.Running
            }
        }
        else -> ThreadActivity.Idle
    }

    fun fromThread(thread: JSONObject): ThreadActivity = parse(thread.optJSONObject("status"))

    fun effective(
        thread: JSONObject, live: ThreadActivity?, running: Boolean, approvals: Int
    ): ThreadActivity = when {
        approvals > 0 -> ThreadActivity.WaitingApproval
        running -> ThreadActivity.Running
        live != null -> live
        else -> fromThread(thread)
    }

    fun isActive(status: ThreadActivity): Boolean =
        status in setOf(ThreadActivity.Running, ThreadActivity.WaitingApproval,
            ThreadActivity.WaitingAnswer)

    /** Recent status is ephemeral; authoritative history remains in app-server. */
    fun updated(
        previous: Map<String, ThreadActivity>, id: String, status: ThreadActivity
    ): Map<String, ThreadActivity> {
        require(id.isNotBlank()) { "会话编号不能为空" }
        return ((previous - id) + (id to status)).entries.takeLast(MAX_RECENT)
            .associate { it.key to it.value }
    }
}
