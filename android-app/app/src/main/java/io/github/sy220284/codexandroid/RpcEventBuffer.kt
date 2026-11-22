package io.github.sy220284.codexandroid

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

/**
 * Event transport used by the real native-process reader and by pressure tests.
 * A full queue suspends the producer until the UI catches up, preserving
 * approvals, task completion and timeline deltas without unbounded memory.
 */
internal object RpcEventBuffer {
    const val CAPACITY = 512

    fun create(): MutableSharedFlow<JSONObject> =
        MutableSharedFlow(
            replay = 0, extraBufferCapacity = CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
}
