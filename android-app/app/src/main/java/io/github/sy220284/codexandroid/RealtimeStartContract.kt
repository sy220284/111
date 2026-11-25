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
