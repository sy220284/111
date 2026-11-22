package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryWorkflowEnhancementsTest {
    @Test fun searchesAreDeduplicatedMostRecentFirstAndLimitedToFive() {
        var queries = emptyList<String>()
        listOf("A", "B", "C", "D", "E", "F", " C ").forEach {
            queries = RecentThreadSearches.promote(queries, it)
        }
        assertEquals(listOf("C", "F", "E", "D", "B"), queries)
        assertEquals(queries, RecentThreadSearches.decode(RecentThreadSearches.encode(queries)))
        assertEquals(queries, RecentThreadSearches.promote(queries, " "))
    }

    @Test fun expansionPersistsOnlyForSameDiffAndValidFileIndices() {
        val saved = DiffExpansionPreferences.encode("diff-1", setOf(4, 1, 99))
        assertEquals(setOf(1, 4), DiffExpansionPreferences.decode(saved, "diff-1", 5))
        assertEquals(emptySet<Int>(), DiffExpansionPreferences.decode(saved, "diff-2", 5))
        assertEquals(setOf(0), DiffExpansionPreferences.decode(null, "new", 1))
        assertFalse(DiffExpansionPreferences.decode(null, "new", 3).isNotEmpty())
    }

    @Test fun bulkCommandsAreWhitelistedAndIdsUnique() {
        assertEquals("thread/archive", ThreadBulkActionPolicy.method("archive"))
        assertEquals("thread/unarchive", ThreadBulkActionPolicy.method("unarchive"))
        assertEquals("thread/delete", ThreadBulkActionPolicy.method("delete"))
        assertEquals(listOf("thread-a", "thread-b"),
            ThreadBulkActionPolicy.uniqueIds(listOf("", " thread-a ", "thread-a", "thread-b")))
        assertTrue(ThreadBulkActionPolicy.display("archive").isNotBlank())
    }

    @Test(expected = IllegalArgumentException::class)
    fun bulkRejectsArbitraryProtocolMethods() {
        ThreadBulkActionPolicy.method("thread/interrupt")
    }
}
