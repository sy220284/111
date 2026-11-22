package io.github.sy220284.codexandroid

/** Remembers the unfinished text while traversing recently executed commands. */
internal data class TerminalHistoryPosition(
    val index: Int = -1,
    val draft: String = ""
)

internal data class TerminalHistoryChoice(
    val command: String,
    val position: TerminalHistoryPosition
)

/** Pure keyboard navigation; oldest to newest traversal never discards an unsent draft. */
internal object TerminalHistoryNavigation {
    fun previous(
        current: String,
        recent: List<String>,
        position: TerminalHistoryPosition
    ): TerminalHistoryChoice? {
        if (recent.isEmpty()) return null
        val nextIndex = (position.index + 1).coerceAtMost(recent.lastIndex)
        return TerminalHistoryChoice(
            recent[nextIndex],
            TerminalHistoryPosition(
                nextIndex, if (position.index < 0) current else position.draft
            )
        )
    }

    fun next(
        recent: List<String>,
        position: TerminalHistoryPosition
    ): TerminalHistoryChoice? {
        if (position.index < 0 || position.index >= recent.size) return null
        return if (position.index == 0) {
            TerminalHistoryChoice(position.draft, TerminalHistoryPosition())
        } else {
            val nextIndex = position.index - 1
            TerminalHistoryChoice(recent[nextIndex], position.copy(index = nextIndex))
        }
    }
}
