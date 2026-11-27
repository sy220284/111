package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ReaderTextIndexTest {
    @Test fun crossChunkSelectionUsesExactSourceEvenWithRepeatedTextAndUnicode() {
        val source = "ab😀重复\n**重复**\n[链接](./file.md)\n尾部"
        val chunks = ReaderTextIndex.chunks(source, 3)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertTrue(chunks.none { Character.isHighSurrogate(it.text.last()) })
        assertEquals(2, ReaderTextIndex.boundary(source, 3))
        val range = ReaderTextIndex.range(source, source.length - 1, 2)
        assertEquals(source.substring(2, source.length - 1), range.copy(source))
        assertEquals(range.copy(source), chunks.mapNotNull { chunk ->
            range.intersect(chunk)?.let { chunk.text.substring(it.first, it.last + 1) }
        }.joinToString(""))
        for (offset in source.indices) {
            val chunk = chunks[ReaderTextIndex.chunkIndex(chunks, offset)]
            assertTrue(offset >= chunk.start && offset < chunk.end)
        }
    }
    @Test fun searchHighlightUsesActualChunkBoundaryAfterSurrogateAdjustment() {
        val source = "ab😀cd"
        val chunks = ReaderTextIndex.chunks(source, 3)
        assertEquals(listOf(0, 2, 5), chunks.map { it.start })
        val hits = FullTextSearchPolicy.find(source, "😀c")
        assertEquals(listOf(2), hits)
        assertEquals(listOf(FullTextSearchPolicy.Slice(0, 3, true)),
            FullTextSearchPolicy.slicesAt(chunks[1].start, chunks[1].text.length, 3, hits, 0))
    }
}
