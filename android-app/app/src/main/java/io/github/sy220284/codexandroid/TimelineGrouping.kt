package io.github.sy220284.codexandroid

/** Groups and their contents are persistent sequences. A delta replaces a
 * logarithmic path in the affected group and outer index, never the group list. */
internal class TimelineGrouping private constructor(
    private val entries: PersistentSequence<TimelineGroupSnapshot>,
    private val starts: PersistentSequence<Int>,
    val lastTurnStart: Int
) {
    val groups: List<List<TimelineItem>> get() = entries
    fun sameIdentityPrefix(other: TimelineGrouping, count: Int): Boolean =
        entries.sameIdentityPrefix(other.entries, count)
    fun append(item: TimelineItem): TimelineGrouping {
        val previous = entries.lastOrNull()
        if (previous == null) return TimelineGrouping(
            PersistentSequence<TimelineGroupSnapshot>().append(TimelineGroupSnapshot.single(item)),
            PersistentSequence<Int>().append(0), 0)
        val sameTurn = item.turnId.isNotBlank() && item.turnId == previous.first().turnId
        return if (sameTurn && previous.isActivity && item.kind in TimelineActivityPolicy.kinds) {
            TimelineGrouping(entries.replace(entries.lastIndex, previous.append(item)), starts, lastTurnStart)
        } else {
            TimelineGrouping(entries.append(TimelineGroupSnapshot.single(item)),
                starts.append(starts.last() + previous.size), if (sameTurn) lastTurnStart else entries.size)
        }
    }
    fun replace(index: Int, item: TimelineItem): TimelineGrouping {
        val found = starts.binarySearch(index)
        val group = if (found >= 0) found else -found - 2
        return TimelineGrouping(entries.replace(group, entries[group].replace(index - starts[group], item)), starts, lastTurnStart)
    }
    companion object {
        fun from(items: List<TimelineItem>): TimelineGrouping {
            var result = TimelineGrouping(PersistentSequence(), PersistentSequence(), 0)
            for (item in items) result = result.append(item)
            return result
        }
    }
}
