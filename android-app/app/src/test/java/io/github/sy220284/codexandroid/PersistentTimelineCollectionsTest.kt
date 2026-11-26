package io.github.sy220284.codexandroid

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PersistentTimelineCollectionsTest {
    @Test fun randomizedIndexUpdatesPreserveOldSnapshots() {
        val random = Random(42)
        var index = PersistentIndex<String, Int>()
        val expected = mutableMapOf<String, Int>()
        repeat(20000) {
            val key = "id-${random.nextInt(1000)}"
            val prior = index
            val old = expected[key]
            if (random.nextBoolean()) { index = index.put(key, it); expected[key] = it }
            else { index = index.remove(key); expected.remove(key) }
            assertEquals(old, prior[key])
            assertEquals(expected[key], index[key])
            if (it % 100 == 0) repeat(1000) { n -> assertEquals(expected["id-$n"], index["id-$n"]) }
        }
    }

    @Test fun longSequenceKeepsListEqualityAndIndependentSnapshots() {
        val random = Random(42)
        val plain = (0 until 5000).toList()
        var seq = PersistentSequence.from(plain)
        val baseline = seq
        assertEquals(plain, seq)
        assertEquals(seq, plain)
        assertEquals(plain.hashCode(), seq.hashCode())
        assertEquals(seq, PersistentSequence.from(plain))
        repeat(3000) {
            val pos = random.nextInt(seq.size)
            val old = seq[pos]
            val previous = seq
            seq = seq.replace(pos, -it - 1)
            assertEquals(old, previous[pos])
            assertEquals(-it - 1, seq[pos])
            assertNotEquals(previous, seq)
            assertEquals(pos, baseline[pos])
        }
    }

    @Test fun largeActivityUpdateRetainsOtherGroupsAndIds() {
        val prefix = TimelineItem("old", "user", "previous", turnId = "history")
        val items = listOf(prefix) + (0 until 5000).map { i ->
            TimelineItem("step-$i", "commandExecution", "step", turnId = "active")
        }
        val before = IndexedTimeline.from(items)
        val nextItem = before[3000].copy(text = "updated")
        val after = before.put(nextItem)
        assertSame(before.grouping.groups.first(), after.grouping.groups.first())
        assertEquals(nextItem, after.byId(nextItem.id))
        assertEquals("step", before.byId(nextItem.id)?.text)
        assertEquals(items.toMutableList().apply { set(3000, nextItem) }, after)
        assertEquals(after, IndexedTimeline.from(after.toList()))
    }
}
