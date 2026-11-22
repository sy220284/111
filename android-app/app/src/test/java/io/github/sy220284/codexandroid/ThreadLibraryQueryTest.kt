package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ThreadLibraryQueryTest {
    @Test fun filtersWithDelimitersNeverCollide() {
        assertNotEquals(
            ThreadLibraryQuery.key(false, "a:b", "c", null),
            ThreadLibraryQuery.key(false, "a", "b:c", null))
        assertNotEquals(
            ThreadLibraryQuery.key(false, "test", "", null),
            ThreadLibraryQuery.key(false, "test", "", ""))
        assertNotEquals(
            ThreadLibraryQuery.fullTextKey("a:b"),
            ThreadLibraryQuery.fullTextKey("a"))
    }

    @Test fun repeatedIdentityIsStable() {
        val first = ThreadLibraryQuery.key(true, "测试", "组:一", "cursor-2")
        assertEquals(first, ThreadLibraryQuery.key(true, "测试", "组:一", "cursor-2"))
        assertTrue(first.startsWith("library:"))
    }
}
