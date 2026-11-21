package io.github.sy220284.codexandroid

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Codex Thread.updatedAt / createdAt are Unix seconds, not ISO text.
 * A display formatter is deliberately separate from server record storage.
 */
object ThreadDisplayPolicy {
    private val display = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun updatedAt(
        updatedAt: Any?,
        createdAt: Any?,
        zone: ZoneId = ZoneId.systemDefault()
    ): String? {
        fun parse(value: Any?): Long? {
            val seconds = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }
            return seconds?.takeIf { it > 0 }
        }
        val epoch = parse(updatedAt) ?: parse(createdAt) ?: return null
        return display.withZone(zone).format(Instant.ofEpochSecond(epoch))
    }

    /** An active Codex turn owns its thread until it completes or is stopped. */
    fun canOpenThread(running: Boolean, currentId: String?, targetId: String): Boolean =
        targetId.isNotBlank() && (!running || currentId == targetId)

    /** Keep any newly edited text; clear only the draft the server accepted. */
    fun afterAcceptedDraft(current: String, submitted: String): String =
        if (current == submitted) "" else current
}
