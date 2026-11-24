package io.github.sy220284.codexandroid

/** Reuse every unchanged activity group. A text/status delta never regroups the history. */
internal class TimelineGrouping private constructor(
    val groups: List<List<TimelineItem>>,
    private val starts: List<Int>
) {
    fun append(item: TimelineItem): TimelineGrouping {
        val previous = groups.lastOrNull()
        if (previous == null) return TimelineGrouping(listOf(listOf(item)), listOf(0))
        val next = groups.toMutableList()
        return if (joins(previous.first(), item)) {
            next[next.lastIndex] = previous + item
            TimelineGrouping(next, starts)
        } else {
            next.add(listOf(item))
            TimelineGrouping(next, starts + (starts.last() + previous.size))
        }
    }
    fun replace(index: Int, item: TimelineItem): TimelineGrouping {
        val found = starts.binarySearch(index)
        val group = if (found >= 0) found else -found - 2
        val next = groups.toMutableList()
        next[group] = groups[group].toMutableList().apply { set(index - starts[group], item) }
        return TimelineGrouping(next, starts)
    }
    companion object {
        private val activity = setOf("reasoning", "commandExecution", "fileChange", "mcpToolCall",
            "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "sleep", "hookPrompt")
        private fun joins(first: TimelineItem, item: TimelineItem) =
            item.kind in activity && first.kind in activity && item.turnId.isNotBlank() && item.turnId == first.turnId
        fun from(items: List<TimelineItem>): TimelineGrouping {
            val groups = mutableListOf<MutableList<TimelineItem>>()
            val starts = mutableListOf<Int>()
            for ((index, item) in items.withIndex()) {
                val last = groups.lastOrNull()
                if (last != null && joins(last.first(), item)) last.add(item)
                else { starts.add(index); groups.add(mutableListOf(item)) }
            }
            return TimelineGrouping(groups, starts)
        }
    }
}
