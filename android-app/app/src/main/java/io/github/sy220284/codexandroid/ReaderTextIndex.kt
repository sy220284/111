package io.github.sy220284.codexandroid

/** Absolute UTF-16 offsets are owned by the unchanged native source, not
 * composed nodes. A selection can span any number of unloaded lazy items. */
internal object ReaderTextIndex {
    data class Chunk(val start: Int, val text: String) { val end: Int get() = start + text.length }
    data class Range(val start: Int, val end: Int) {
        fun intersect(chunk: Chunk): IntRange? {
            val a = maxOf(start, chunk.start)
            val b = minOf(end, chunk.end)
            return if (a < b) (a - chunk.start) until (b - chunk.start) else null
        }
        fun copy(source: String): String = source.substring(start, end)
    }
    fun chunks(source: String, size: Int = FullTextSearchPolicy.CHUNK_SIZE): List<Chunk> {
        require(size >= 2)
        if (source.isEmpty()) return listOf(Chunk(0, ""))
        return buildList {
            var start = 0
            while (start < source.length) {
                var end = minOf(source.length, start + size)
                if (end < source.length && Character.isHighSurrogate(source[end - 1]) &&
                    Character.isLowSurrogate(source[end])) end--
                add(Chunk(start, source.substring(start, end)))
                start = end
            }
        }
    }
    fun chunkIndex(chunks: List<Chunk>, offset: Int): Int {
        val found = chunks.binarySearchBy(offset) { it.start }
        return (if (found >= 0) found else -found - 2).coerceIn(0, chunks.lastIndex)
    }
    fun boundary(source: String, offset: Int): Int {
        val index = offset.coerceIn(0, source.length)
        return if (index in 1 until source.length && Character.isHighSurrogate(source[index - 1]) &&
            Character.isLowSurrogate(source[index])) index - 1 else index
    }
    fun range(source: String, first: Int, last: Int): Range {
        val a = boundary(source, first)
        val b = boundary(source, last)
        return Range(minOf(a, b), maxOf(a, b))
    }
}
