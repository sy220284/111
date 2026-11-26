package io.github.sy220284.codexandroid

/** Groups and their contents are persistent sequences. A delta replaces a
 * logarithmic path in the affected group and outer index, never the group list. */
internal class TimelineGrouping private constructor(
    private val entries: PersistentSequence<PersistentSequence<TimelineItem>>,
    private val starts: PersistentSequence<Int>
) {
    val groups: List<List<TimelineItem>> get() = entries
    fun sameIdentityPrefix(other: TimelineGrouping, count: Int): Boolean =
        entries.sameIdentityPrefix(other.entries, count)
    fun append(item: TimelineItem): TimelineGrouping {
        val previous = entries.lastOrNull()
        if (previous == null) return TimelineGrouping(
            PersistentSequence<PersistentSequence<TimelineItem>>().append(PersistentSequence<TimelineItem>().append(item)),
            PersistentSequence<Int>().append(0))
        return if (joins(previous.first(), item)) {
            TimelineGrouping(entries.replace(entries.lastIndex, previous.append(item)), starts)
        } else {
            TimelineGrouping(entries.append(PersistentSequence<TimelineItem>().append(item)),
                starts.append(starts.last() + previous.size))
        }
    }
    fun replace(index: Int, item: TimelineItem): TimelineGrouping {
        val found = starts.binarySearch(index)
        val group = if (found >= 0) found else -found - 2
        return TimelineGrouping(entries.replace(group, entries[group].replace(index - starts[group], item)), starts)
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
            return TimelineGrouping(PersistentSequence.from(groups.map { PersistentSequence.from(it) }),
                PersistentSequence.from(starts))
        }
    }
}
