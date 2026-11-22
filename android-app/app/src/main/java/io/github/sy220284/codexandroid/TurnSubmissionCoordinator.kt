package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * Owns the two-RPC send sequence so a timeout cannot masquerade as an explicit
 * rejection. The caller retains its draft until Accepted, and preserves
 * per-thread ownership whenever turn/start may have reached the core.
 */
internal sealed interface TurnSubmissionOutcome {
    data class Accepted(
        val threadId: String,
        val turnId: String,
        val newlyCreatedThreadId: String?
    ) : TurnSubmissionOutcome

    data class Failed(
        val threadId: String?,
        val newlyCreatedThreadId: String?,
        val turnRequestDispatched: Boolean,
        val failure: Exception
    ) : TurnSubmissionOutcome {
        val requiresReconciliation: Boolean
            get() = turnRequestDispatched &&
                !FirstTurnRecoveryPolicy.explicitlyRejected(failure)
    }
}

internal class TurnSubmissionCoordinator(
    private val request: suspend (String, JSONObject) -> JSONObject
) {
    suspend fun submit(
        existingThreadId: String?,
        createThreadParams: () -> JSONObject,
        turnParams: (String) -> JSONObject,
        onThreadCreated: (String) -> Unit
    ): TurnSubmissionOutcome {
        var current = existingThreadId
        var created: String? = null
        var dispatched = false
        return try {
            require(current == null || current.isNotBlank()) { "会话编号不能为空" }
            if (current == null) {
                val response = request("thread/start", createThreadParams())
                val id = response.optJSONObject("thread")?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "thread/start：内核未返回有效会话编号，请检查会话资料库")
                created = id
                current = id
                onThreadCreated(id)
            }
            val id = requireNotNull(current)
            val params = turnParams(id)
            dispatched = true
            val response = request("turn/start", params)
            val turnId = response.optJSONObject("turn")?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "turn/start：内核未返回有效回合编号，请先核对任务是否已经启动")
            TurnSubmissionOutcome.Accepted(id, turnId, created)
        } catch (failure: Exception) {
            TurnSubmissionOutcome.Failed(current, created, dispatched, failure)
        }
    }
}
