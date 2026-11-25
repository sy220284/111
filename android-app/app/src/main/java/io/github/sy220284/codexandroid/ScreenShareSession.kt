package io.github.sy220284.codexandroid

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One consented capture. A bounded sequence stays in memory until an authorized action uses it. */
internal class ScreenShareSession {
    data class State(val token: String? = null, val owner: String? = null, val revision: Long = 0,
                     val capturing: Boolean = false, val hasFrame: Boolean = false, val message: String = "",
                     val sequence: Long = 0)
    data class Frame(val sequence: Long, val timeMs: Long, val bytes: ByteArray)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val frames = ArrayDeque<Frame>()
    private var origin: Long? = null

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

    @Synchronized fun publish(token: String, bytes: ByteArray, capturedAtMs: Long = System.nanoTime() / 1_000_000) {
        val current = mutableState.value
        if (current.token != token || !current.capturing || bytes.isEmpty() || bytes.size > 1_500_000) return
        if (frames.lastOrNull()?.bytes?.contentEquals(bytes) == true) return
        val start = origin ?: capturedAtMs.also { origin = it }
        val sequence = current.sequence + 1
        if (frames.size == 6) frames.removeFirst().bytes.fill(0)
        frames.addLast(Frame(sequence, (capturedAtMs - start).coerceAtLeast(0), bytes.copyOf()))
        mutableState.value = current.copy(hasFrame = true, sequence = sequence)
    }

    @Synchronized fun snapshot(owner: String, revision: Long): ByteArray? {
        val current = mutableState.value
        return if (current.capturing && current.owner == owner && current.revision == revision)
            frames.lastOrNull()?.bytes?.copyOf() else null
    }

    @Synchronized fun framesAfter(token: String, owner: String, revision: Long, sequence: Long): List<Frame> {
        val current = mutableState.value
        if (!current.capturing || current.token != token || current.owner != owner || current.revision != revision) return emptyList()
        val fresh = frames.filter { it.sequence > sequence }
        val chosen = if (fresh.size <= 3) fresh else listOf(fresh.first(), fresh[fresh.size / 2], fresh.last())
        return chosen.map { it.copy(bytes = it.bytes.copyOf()) }
    }

    private fun clearFrames() {
        frames.forEach { it.bytes.fill(0) }
        frames.clear()
    }

    @Synchronized fun invalidate(token: String) {
        val current = mutableState.value
        if (current.token != token) return
        clearFrames()
        mutableState.value = current.copy(hasFrame = false)
    }

    @Synchronized fun stopping(token: String, message: String = "") {
        val current = mutableState.value
        if (current.token != token) return
        clearFrames()
        mutableState.value = current.copy(capturing = false, hasFrame = false, message = message)
    }

    @Synchronized fun finish(token: String, message: String = "") {
        val current = mutableState.value
        if (current.token != token) return
        clearFrames()
        origin = null
        mutableState.value = State(message = message.ifBlank { current.message })
    }
}
internal val screenShareSession = ScreenShareSession()
