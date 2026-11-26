package io.github.sy220284.codexandroid

/** 搜索原文而不是逐段搜索，跨渲染分块的匹配也能正确显示。 */
internal object FullTextSearchPolicy {
    const val CHUNK_SIZE = 3200
    const val MAX_MATCHES = 2000
    data class Slice(val start: Int, val end: Int, val active: Boolean)

    fun find(text: String, query: String): List<Int> {
        if (query.isBlank()) return emptyList()
        return buildList {
            var cursor = 0
            while (cursor < text.length && size < MAX_MATCHES) {
                val found = text.indexOf(query, cursor, ignoreCase = true)
                if (found < 0) break
                add(found)
                cursor = found + query.length
            }
        }
    }

    fun slices(chunkIndex: Int, chunkLength: Int, queryLength: Int,
        hits: List<Int>, currentMatch: Int): List<Slice> {
        return slicesAt(chunkIndex * CHUNK_SIZE, chunkLength, queryLength, hits, currentMatch)
    }

    fun slicesAt(offset: Int, chunkLength: Int, queryLength: Int,
        hits: List<Int>, currentMatch: Int): List<Slice> {
        if (queryLength <= 0) return emptyList()
        val endOffset = offset + chunkLength
        return hits.mapIndexedNotNull { index, found ->
            val start = maxOf(offset, found)
            val end = minOf(endOffset, found + queryLength)
            if (start < end) Slice(start - offset, end - offset, index == currentMatch)
            else null
        }
    }
}
