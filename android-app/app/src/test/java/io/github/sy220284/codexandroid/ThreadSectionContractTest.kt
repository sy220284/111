package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadSectionContractTest {
    @Test fun defaultThreadListIsNotConstrainedToAnySection() {
        val args = ThreadSectionContract.threadList(false, "code", "cursor1", "")
        assertFalse(args.has("sectionId"))
        assertEquals("code", args.getString("searchTerm"))
        assertEquals("cursor1", args.getString("cursor"))
        assertFalse(args.getBoolean("archived"))
    }

    @Test fun ungroupedThreadsAreRequestedWithJsonNull() {
        val args = ThreadSectionContract.threadList(true, "", null, ThreadSectionContract.UNGROUPED)
        assertTrue(args.has("sectionId"))
        assertTrue(args.isNull("sectionId"))
        assertTrue(args.getBoolean("archived"))
        assertFalse(args.has("cursor"))
    }

    @Test fun sectionFilterAndMoveUseRealServerKeys() {
        val args = ThreadSectionContract.threadList(false, "", null, "section-a")
        assertEquals("section-a", args.getString("sectionId"))
        val move = ThreadSectionContract.move("thread-1", "section-b")
        assertEquals("thread-1", move.getString("threadId"))
        assertEquals("section-b", move.getString("sectionId"))
        assertTrue(ThreadSectionContract.move("thread-1", null).isNull("sectionId"))
    }

    @Test fun namesMustBeMeaningfulAndAreTrimmedBeforeRpc() {
        assertEquals("研发任务", ThreadSectionContract.create("  研发任务  ").getString("name"))
        assertEquals("整理", ThreadSectionContract.rename("section-a", " 整理 ").getString("name"))
        assertThrows(IllegalArgumentException::class.java) {
            ThreadSectionContract.create("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadSectionContract.create("X".repeat(121))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadSectionContract.move("", "section")
        }
    }

    @Test fun destructiveDeleteMustIncludeExactSectionId() {
        val args: JSONObject = ThreadSectionContract.delete("section-a")
        assertEquals("section-a", args.getString("sectionId"))
        assertThrows(IllegalArgumentException::class.java) { ThreadSectionContract.delete(" ") }
    }
}
