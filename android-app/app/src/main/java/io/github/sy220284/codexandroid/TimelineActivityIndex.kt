package io.github.sy220284.codexandroid

internal object TimelineActivityPolicy {
    val kinds = setOf("reasoning", "commandExecution", "fileChange", "mcpToolCall",
        "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "sleep", "hookPrompt")
    val runningStates = setOf("inProgress", "in_progress", "running", "streaming", "sending")
    fun running(item: TimelineItem) = item.status in runningStates
    const val GROUP_PAGE_SIZE = 12
    const val RECENT_COMPLETED = 5
}

/** A group is an immutable record list plus two ordered activity indexes. Updating
 * a text delta retains both indexes unless its ID or running state changes. */
internal class TimelineGroupSnapshot private constructor(
    private val items: PersistentSequence<TimelineItem>,
    val isActivity: Boolean,
    private val running: PersistentIndex<Int, Position>,
    private val completed: PersistentIndex<Int, Position>
) : AbstractList<TimelineItem>() {
    data class Position(val index: Int, val id: String)
    override val size get() = items.size
    override fun get(index: Int) = items[index]
    override fun iterator() = items.iterator()
    val hasRunning: Boolean get() = if (isActivity) running.values().hasNext()
        else TimelineActivityPolicy.running(first())
    fun runningPositions(): Sequence<Position> = running.values().asSequence()
    fun completedPositions(): Sequence<Position> = completed.values(reverse = true).asSequence()

    fun append(item: TimelineItem): TimelineGroupSnapshot {
        check(isActivity && item.kind in TimelineActivityPolicy.kinds)
        val position = Position(size, item.id)
        val active = TimelineActivityPolicy.running(item)
        return TimelineGroupSnapshot(items.append(item), true,
            if (active) running.put(size, position) else running,
            if (active) completed else completed.put(size, position))
    }

    fun replace(index: Int, item: TimelineItem): TimelineGroupSnapshot {
        val previous = items[index]
        val wasRunning = TimelineActivityPolicy.running(previous)
        val active = TimelineActivityPolicy.running(item)
        val nextItems = items.replace(index, item)
        if (!isActivity || (wasRunning == active && previous.id == item.id))
            return TimelineGroupSnapshot(nextItems, isActivity, running, completed)
        val position = Position(index, item.id)
        return TimelineGroupSnapshot(nextItems, true,
            if (active) running.put(index, position) else running.remove(index),
            if (active) completed.remove(index) else completed.put(index, position))
    }

    override fun equals(other: Any?): Boolean = this === other ||
        if (other is TimelineGroupSnapshot) items == other.items else super.equals(other)
    override fun hashCode(): Int = super.hashCode()

    companion object {
        fun single(item: TimelineItem): TimelineGroupSnapshot {
            val activity = item.kind in TimelineActivityPolicy.kinds
            val active = TimelineActivityPolicy.running(item)
            val position = Position(0, item.id)
            val empty = PersistentIndex<Int, Position>()
            return TimelineGroupSnapshot(PersistentSequence<TimelineItem>().append(item), activity,
                if (activity && active) empty.put(0, position) else empty,
                if (activity && !active) empty.put(0, position) else empty)
        }
    }
}

/** Build only the running positions and five newest finished positions. Each
 * collapsed activity row looks up its own visible IDs without scanning its records. */
internal object TimelineActivityVisibility {
    fun byGroup(groups: List<List<TimelineItem>>): Map<Int, Map<Int, Set<String>>> {
        val visible = mutableMapOf<Int, MutableMap<Int, MutableSet<String>>>()
        fun add(group: Int, position: TimelineGroupSnapshot.Position) {
            visible.getOrPut(group) { mutableMapOf() }
                .getOrPut(position.index / TimelineActivityPolicy.GROUP_PAGE_SIZE) { mutableSetOf() }
                .add(position.id)
        }
        for ((index, group) in groups.withIndex()) {
            val running = if (group is TimelineGroupSnapshot) group.runningPositions() else
                group.asSequence().withIndex().filter { it.value.kind in TimelineActivityPolicy.kinds &&
                    TimelineActivityPolicy.running(it.value) }.map { TimelineGroupSnapshot.Position(it.index, it.value.id) }
            for (position in running) add(index, position)
        }
        var remaining = TimelineActivityPolicy.RECENT_COMPLETED
        for (index in groups.indices.reversed()) {
            if (remaining == 0) break
            val group = groups[index]
            val completed = if (group is TimelineGroupSnapshot) group.completedPositions() else
                group.indices.reversed().asSequence().filter { group[it].kind in TimelineActivityPolicy.kinds &&
                    !TimelineActivityPolicy.running(group[it]) }.map { TimelineGroupSnapshot.Position(it, group[it].id) }
            for (position in completed.take(remaining)) {
                add(index, position)
                remaining--
            }
        }
        return visible
    }
}
