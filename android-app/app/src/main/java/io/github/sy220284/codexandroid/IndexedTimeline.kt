package io.github.sy220284.codexandroid

/** Immutable ordered snapshots. Native ID, segment and group indexes share
 * unchanged branches; text deltas replace only one bounded leaf segment. */
internal class IndexedTimeline private constructor(
    private val segments: PersistentSequence<List<TimelineItem>>,
    private val positions: PersistentIndex<String, Int>,
    private val turns: PersistentIndex<String, PersistentSequence<Int>>,
    override val size: Int,
    internal val grouping: TimelineGrouping
) : AbstractList<TimelineItem>() {
    override fun get(index: Int): TimelineItem {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Timeline index $index")
        return segments[index / SEGMENT][index % SEGMENT]
    }
    override fun iterator(): Iterator<TimelineItem> = object : Iterator<TimelineItem> {
        private val outer = segments.iterator()
        private var inner: Iterator<TimelineItem> = emptyList<TimelineItem>().iterator()
        override fun hasNext(): Boolean {
            while (!inner.hasNext() && outer.hasNext()) inner = outer.next().iterator()
            return inner.hasNext()
        }
        override fun next(): TimelineItem {
            if (!hasNext()) throw NoSuchElementException()
            return inner.next()
        }
    }
    fun byId(id: String): TimelineItem? = positions[id]?.let(::get)
    fun positionOf(id: String): Int = positions[id] ?: -1
    fun lastIndexOfTurn(turnId: String): Int = turns[turnId]?.lastOrNull() ?: -1
    fun inTurn(turnId: String): Sequence<TimelineItem> = turns[turnId]?.asSequence()?.map(::get) ?: emptySequence()
    fun put(item: TimelineItem, clientId: String = ""): IndexedTimeline {
        val index = positions[item.id] ?: if (item.kind == "user" && clientId.isNotBlank())
            positions["pending-$clientId"]?.takeIf { get(it).kind == "user" } else null
        if (index == null) {
            val next = if (size % SEGMENT == 0) segments.append(listOf(item))
                else segments.replace(segments.lastIndex, segments.last() + item)
            val turnPositions = (turns[item.turnId] ?: PersistentSequence()).append(size)
            return IndexedTimeline(next, positions.put(item.id, size), turns.put(item.turnId, turnPositions), size + 1, grouping.append(item))
        }
        val old = get(index)
        if (old == item) return this
        val segment = index / SEGMENT
        val next = segments.replace(segment, segments[segment].toMutableList().apply { set(index % SEGMENT, item) })
        val ids = if (old.id == item.id) positions else positions.remove(old.id).put(item.id, index)
        // A changed turn assignment is rare and changes both the turn index and grouping.
        if (old.turnId != item.turnId) return from(next.flatten())
        val nextGrouping = if (old.kind == item.kind) grouping.replace(index, item)
            else TimelineGrouping.from(next.flatten())
        return IndexedTimeline(next, ids, turns, size, nextGrouping)
    }
    override fun equals(other: Any?): Boolean = this === other || if (other is IndexedTimeline)
        size == other.size && segments == other.segments else super.equals(other)
    override fun hashCode(): Int = super.hashCode()

    companion object {
        private const val SEGMENT = 32
        fun from(items: List<TimelineItem>): IndexedTimeline {
            if (items is IndexedTimeline) return items
            var positions = PersistentIndex<String, Int>()
            var turns = PersistentIndex<String, PersistentSequence<Int>>()
            items.forEachIndexed { index, item ->
                positions = positions.put(item.id, index)
                turns = turns.put(item.turnId, (turns[item.turnId] ?: PersistentSequence()).append(index))
            }
            return IndexedTimeline(PersistentSequence.from(items.chunked(SEGMENT)), positions, turns,
                items.size, TimelineGrouping.from(items))
        }
    }
}
