package io.github.sy220284.codexandroid

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One explicitly consented screen capture. Frames stay in memory until the owner attaches one. */
internal class ScreenShareSession {
    data class State(val token: String? = null, val owner: String? = null, val revision: Long = 0,
                     val capturing: Boolean = false, val hasFrame: Boolean = false, val message: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var frame: ByteArray? = null

    @Synchronized fun reserve(owner: String, revision: Long): String? {
        if (mutableState.value.token != null) return null
        val token = UUID.randomUUID().toString()
        mutableState.value = State(token, owner, revision)
        return token
    }

    @Synchronized fun started(token: String): Boolean {
        val current = mutableState.value
        if (current.token != token || current.capturing) return false
        mutableState.value = current.copy(capturing = true)
        return true
    }

    @Synchronized fun publish(token: String, bytes: ByteArray) {
        val current = mutableState.value
        if (current.token != token || !current.capturing) return
        frame?.fill(0)
        frame = bytes.copyOf()
        if (!current.hasFrame) mutableState.value = current.copy(hasFrame = true)
    }

    @Synchronized fun snapshot(owner: String, revision: Long): ByteArray? {
        val current = mutableState.value
        return if (current.capturing && current.owner == owner && current.revision == revision) frame?.copyOf() else null
    }

    @Synchronized fun invalidate(token: String) {
        val current = mutableState.value
        if (current.token != token) return
        frame?.fill(0)
        frame = null
        mutableState.value = current.copy(hasFrame = false)
    }

    @Synchronized fun finish(token: String, message: String = "") {
        if (mutableState.value.token != token) return
        frame?.fill(0)
        frame = null
        mutableState.value = State(message = message)
    }
}

internal val screenShareSession = ScreenShareSession()
