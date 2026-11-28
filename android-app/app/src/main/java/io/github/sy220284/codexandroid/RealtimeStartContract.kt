package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Current ThreadRealtimeStartParams no longer accepts a session voice override. */
internal object RealtimeStartContract {
    fun params(threadId: String): JSONObject {
        require(threadId.isNotBlank())
        return JSONObject().put("threadId", threadId).put("outputModality", "audio")
            .put("transport", JSONObject().put("type", "websocket"))
    }
}

/** Distinguishes a queued start from one that may have reached the native session. */
internal class RealtimeStartSubmission {
    private enum class State { Queued, Revoked, Admitted, Rejected }

    private var state = State.Queued

    @Synchronized fun admit(): Boolean {
        if (state != State.Queued) return false
        state = State.Admitted
        return true
    }

    @Synchronized fun revokeQueued(): Boolean {
        if (state != State.Queued) return false
        state = State.Revoked
        return true
    }

    @Synchronized fun reject() {
        state = State.Rejected
    }

    @Synchronized fun mayHaveStarted(): Boolean = state == State.Admitted
}
