package io.github.sy220284.codexandroid

import java.time.Instant
import java.time.LocalDate
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

    /** Date headings are based on real server timestamps in the device time zone. */
    fun dayGroup(
        updatedAt: Any?, createdAt: Any?,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val value = updatedAt?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: createdAt?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            ?: return "日期未知"
        val day = Instant.ofEpochSecond(value).atZone(zone).toLocalDate()
        return when {
            day == today -> "今天"
            day == today.minusDays(1) -> "昨天"
            else -> "更早"
        }
    }

    /** Browsing another thread is independent of ongoing turns in other threads. */
    fun canOpenThread(currentId: String?, targetId: String): Boolean =
        targetId.isNotBlank() && currentId != targetId

    /** Keep any newly edited text; clear only the draft the server accepted. */
    fun afterAcceptedDraft(current: String, submitted: String): String =
        if (current == submitted) "" else current
}
