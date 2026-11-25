package io.github.sy220284.codexandroid

/** All methods are called by the UI/main coroutine. A notification or disconnect
 * invalidates every older status response, even if the environment ID is unchanged. */
internal class StatusResponseOrder {
    private var revision = 0L
    fun begin(): Long = ++revision
    fun invalidate() { revision++ }
    fun accepts(token: Long, connected: Boolean): Boolean = connected && token == revision
}

/** Audio collected before a mute/unmute boundary must never enter a new request. */
internal class VoiceUploadGate {
    private data class Permission(val revision: Long, val muted: Boolean)
    @Volatile private var permission = Permission(0L, false)
    val revision: Long get() = permission.revision
    val muted: Boolean get() = permission.muted
    @Synchronized fun setMuted(value: Boolean) {
        if (permission.muted != value) permission = Permission(permission.revision + 1, value)
    }
    @Synchronized fun invalidate() { permission = permission.copy(revision = permission.revision + 1) }
    fun accepts(packetRevision: Long): Boolean = permission.let { !it.muted && packetRevision == it.revision }
}

/** A partially successful move is a transaction with one immutable destination.
 * Retrying only failed versions at another destination would split a logical thread. */
internal data class SectionMoveAttempt(val target: String?, val label: String) {
    fun accepts(candidate: String?): Boolean = target == candidate
}
