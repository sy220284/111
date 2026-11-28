package io.github.sy220284.codexandroid

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

/**
 * Event transport used by the real native-process reader and by pressure tests.
 * A full queue suspends the producer until the UI catches up, preserving
 * approvals, task completion and timeline deltas without unbounded memory.
 */
internal object RpcEventBuffer {
    const val CAPACITY = 512

    suspend fun enqueue(inbox: Channel<JSONObject>, frame: JSONObject,
        drainTimeoutMs: Long = RpcScheduling.WRITE_TIMEOUT_MS) {
        if (inbox.trySend(frame).isSuccess) return
        check(withTimeoutOrNull(drainTimeoutMs) {
            inbox.send(frame)
            true
        } == true) { "内核事件积压未能排空，需重新同步会话" }
    }

    fun create(): MutableSharedFlow<JSONObject> =
        MutableSharedFlow(
            replay = 0, extraBufferCapacity = CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
}
