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
    var revision = 0L
        private set
    var muted = false
        private set
    fun setMuted(value: Boolean) {
        if (value != muted) {
            muted = value
            revision++
        }
    }
    fun accepts(packetRevision: Long): Boolean = !muted && packetRevision == revision
}

/** A partially successful move is a transaction with one immutable destination.
 * Retrying only failed versions at another destination would split a logical thread. */
internal data class SectionMoveAttempt(val target: String?, val label: String) {
    fun accepts(candidate: String?): Boolean = target == candidate
}
