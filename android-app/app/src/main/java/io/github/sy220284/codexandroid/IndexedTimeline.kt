package io.github.sy220284.codexandroid

/** Immutable ordered snapshots; native ID lookup and replacement copy one segment. */
internal class IndexedTimeline private constructor(
    private val segments: List<List<TimelineItem>>,
    private val positions: Map<String, Int>,
    override val size: Int
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
            val next = segments.toMutableList()
            if (size % SEGMENT == 0) next.add(listOf(item))
            else next[next.lastIndex] = next.last() + item
            return IndexedTimeline(next, positions + (item.id to size), size + 1)
        }
        val old = get(index)
        if (old == item) return this
        val next = segments.toMutableList()
        val segment = index / SEGMENT
        next[segment] = next[segment].toMutableList().apply { set(index % SEGMENT, item) }
        val ids = if (old.id == item.id) positions else positions - old.id + (item.id to index)
        return IndexedTimeline(next, ids, size)
    }
    companion object {
        private const val SEGMENT = 32
        fun from(items: List<TimelineItem>): IndexedTimeline = if (items is IndexedTimeline) items else
            IndexedTimeline(items.chunked(SEGMENT), items.mapIndexed { i, item -> item.id to i }.toMap(), items.size)
    }
}
