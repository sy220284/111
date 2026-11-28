package io.github.sy220284.codexandroid

/** Capture-thread accumulator for partial, non-blocking PCM reads. */
internal class RealtimePcmFrame(private val gate: VoiceUploadGate, byteCount: Int) {
    val bytes = ByteArray(byteCount)
    private var filled = 0
    private var revision = gate.revision

    fun read(samples: (ByteArray, Int, Int) -> Int): Int {
        val current = gate.revision
        if (current != revision) {
            filled = 0
            revision = current
        }
        val size = samples(bytes, filled, bytes.size - filled)
        check(size >= 0) { "麦克风录音已中断" }
        // Discard fragments collected while muted or spanning a revision change.
        filled = if (gate.accepts(revision)) filled + size else 0
        return size
    }

    fun takeCompleteRevision(): Long? {
        if (filled != bytes.size) return null
        filled = 0
        return revision.takeIf(gate::accepts)
    }
}

/** Normalized RMS of signed little-endian PCM samples. */
internal fun pcm16Level(bytes: ByteArray): Float {
    val count = bytes.size / 2
    if (count == 0) return 0f
    var squares = 0.0
    for (i in 0 until count) {
        val sample = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
        squares += sample * sample
    }
    return (kotlin.math.sqrt(squares / count) * 3.0).coerceIn(0.0, 1.0).toFloat()
}
