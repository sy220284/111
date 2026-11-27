package io.github.sy220284.codexandroid

/** One span per logical row, not one allocation/index entry per rendered page.
 * Immutable page lists remain the authority; physical items are constructed on demand. */
internal class TimelineDisplayRows(private val spans: List<Span>) : AbstractList<TimelineDisplayRow>() {
    data class Span(val row: ProgressiveReplyProjection.Row, val pages: List<ReplyRenderPage>) {
        val count get() = maxOf(1, pages.size)
    }
    private val starts = IntArray(spans.size + 1)
    private val logical = HashMap<String, Int>(spans.size)
    init {
        spans.forEachIndexed { index, span ->
            starts[index + 1] = Math.addExact(starts[index], span.count)
            logical[span.row.key] = index
        }
    }
    override val size get() = starts.last()
    override fun get(index: Int): TimelineDisplayRow {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Display index $index")
        val found = starts.binarySearch(index)
        val at = if (found >= 0) found else -found - 2
        val span = spans[at]
        val page = index - starts[at]
        return TimelineDisplayRow(span.row, span.pages.getOrNull(page), page, page == span.count - 1)
    }
    fun position(key: String): Int? {
        logical[key]?.let { return starts[it] }
        if (!key.startsWith("reply-page:")) return null
        val start = "reply-page:".length
        val separator = key.indexOf(':', start)
        if (separator < 0) return null
        val length = key.substring(start, separator).toIntOrNull() ?: return null
        val remaining = key.length - separator - 1
        if (length < 0 || length >= remaining) return null
        val end = separator + 1 + length
        if (key[end] != ':') return null
        val row = logical[key.substring(separator + 1, end)] ?: return null
        val page = key.substring(end + 1).toIntOrNull() ?: return null
        if (page !in 1 until spans[row].count) return null
        val index = starts[row] + page
        return index.takeIf { get(it).key == key }
    }
    fun headingPosition(key: String, heading: Int): Int? {
        val index = logical[key] ?: return null
        val pages = spans[index].pages
        if (pages.isEmpty()) return starts[index]
        // firstHeading+headingCount is monotonic, including pages with no headings.
        var low = 0
        var high = pages.size
        while (low < high) {
            val mid = low + (high - low) / 2
            val page = pages[mid]
            if (page.firstHeading + page.headingCount <= heading) low = mid + 1 else high = mid
        }
        return if (low < pages.size && pages[low].ownsHeading(heading)) starts[index] + low else null
    }
    override fun equals(other: Any?): Boolean = this === other ||
        if (other is TimelineDisplayRows) spans == other.spans else super.equals(other)
    override fun hashCode(): Int = super.hashCode()
}
