package io.github.sy220284.codexandroid

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class FullTextSearchIndexTest {
    @Test fun indexedSlicesMatchLinearReference() {
        val random = Random(116)
        repeat(3000) {
            val hits = List(random.nextInt(2000)) { random.nextInt(100000) }.distinct().sorted()
            val offset = random.nextInt(100000)
            val length = random.nextInt(3201)
            val query = random.nextInt(1, 500)
            val active = random.nextInt(hits.size + 1)
            val expected = hits.mapIndexedNotNull { index, found ->
                val start = maxOf(offset, found)
                val end = minOf(offset + length, found + query)
                if (start < end) FullTextSearchPolicy.Slice(start - offset, end - offset, index == active) else null
            }
            assertEquals(expected, FullTextSearchPolicy.slicesAt(offset, length, query, hits, active))
        }
    }
}
