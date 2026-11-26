package io.github.sy220284.codexandroid

/** Immutable ordered snapshots. Native ID, segment and group indexes share
 * unchanged branches; text deltas replace only one bounded leaf segment. */
internal class IndexedTimeline private constructor(
    private val segments: PersistentSequence<List<TimelineItem>>,
    private val positions: PersistentIndex<String, Int>,
    override val size: Int,
    internal val grouping: TimelineGrouping
) : AbstractList<TimelineItem>() {
    override fun get(index: Int): TimelineItem {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Timeline index $index")
        return segments[index / SEGMENT][index % SEGMENT]
    }
    fun byId(id: String): TimelineItem? = positions[id]?.let(::get)
    fun put(item: TimelineItem, clientId: String = ""): IndexedTimeline {
        val index = positions[item.id] ?: if (item.kind == "user" && clientId.isNotBlank())
            positions["pending-$clientId"]?.takeIf { get(it).kind == "user" } else null
        if (index == null) {
            val next = if (size % SEGMENT == 0) segments.append(listOf(item))
                else segments.replace(segments.lastIndex, segments.last() + item)
            return IndexedTimeline(next, positions.put(item.id, size), size + 1, grouping.append(item))
        }
        val old = get(index)
        if (old == item) return this
        val segment = index / SEGMENT
        val next = segments.replace(segment, segments[segment].toMutableList().apply { set(index % SEGMENT, item) })
        val ids = if (old.id == item.id) positions else positions.remove(old.id).put(item.id, index)
        val nextGrouping = if (old.kind == item.kind && old.turnId == item.turnId) grouping.replace(index, item)
            else TimelineGrouping.from(next.flatten())
        return IndexedTimeline(next, ids, size, nextGrouping)
    }
    override fun equals(other: Any?): Boolean = this === other || if (other is IndexedTimeline)
        size == other.size && segments == other.segments else super.equals(other)
    override fun hashCode(): Int = super.hashCode()

    companion object {
        private const val SEGMENT = 32
        fun from(items: List<TimelineItem>): IndexedTimeline {
            if (items is IndexedTimeline) return items
            var positions = PersistentIndex<String, Int>()
            items.forEachIndexed { index, item -> positions = positions.put(item.id, index) }
            return IndexedTimeline(PersistentSequence.from(items.chunked(SEGMENT)), positions,
                items.size, TimelineGrouping.from(items))
        }
    }
}
