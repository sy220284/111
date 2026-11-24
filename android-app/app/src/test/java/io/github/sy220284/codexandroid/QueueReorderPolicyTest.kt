package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class QueueReorderPolicyTest {
    @Test fun reorderKeepsEveryIdAndSupportsDistantMoves() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "d", "a"), QueueReorderPolicy.move(ids, 0, 3))
        assertEquals(listOf("d", "a", "b", "c"), QueueReorderPolicy.move(ids, 3, 0))
        assertNull(QueueReorderPolicy.move(ids, 1, 1))
        assertNull(QueueReorderPolicy.move(listOf("a", "a"), 0, 1))
        assertNull(QueueReorderPolicy.move(listOf("a", ""), 0, 1))
        assertNull(QueueReorderPolicy.move(ids, 0, 4))
    }

    @Test fun dragTargetUsesActualRowPositionsIncludingDifferentHeights() {
        val ids = listOf("a", "b", "c")
        val centers = mapOf("a" to 20f, "b" to 100f, "c" to 290f)
        assertEquals(0, QueueReorderPolicy.nearest(ids, centers, 25f))
        assertEquals(1, QueueReorderPolicy.nearest(ids, centers, 160f))
        assertEquals(2, QueueReorderPolicy.nearest(ids, centers, 260f))
        assertNull(QueueReorderPolicy.nearest(ids, emptyMap(), 100f))
    }
}
