package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ThreadExecutionPolicyTest {
    @Test fun simultaneousThreadsKeepIndependentTurnOwnershipAndAttachments() {
        val first = ThreadExecutionPolicy.reserve(emptyMap(), "one", listOf("/cache/one.png"))
        val second = ThreadExecutionPolicy.reserve(first, "two", listOf("/cache/two.png"))
        val started = ThreadExecutionPolicy.started(
            ThreadExecutionPolicy.started(second, "one", "turn-one"), "two", "turn-two")
        assertEquals("turn-one", started["one"]?.turnId)
        assertEquals("turn-two", started["two"]?.turnId)
        assertEquals(listOf("/cache/one.png"), started["one"]?.images)
        val finished = ThreadExecutionPolicy.finish(started, "two")
        assertNull(finished["two"])
        assertEquals("turn-one", finished["one"]?.turnId)
        assertEquals(listOf("/cache/one.png"), finished["one"]?.images)
    }

    @Test fun repeatedTurnStartDoesNotEraseImagesAndDuplicateSubmissionIsRejected() {
        val reserved = ThreadExecutionPolicy.reserve(emptyMap(), "one", listOf("img"))
        val started = ThreadExecutionPolicy.started(reserved, "one", "turn-one")
        assertEquals(listOf("img"), ThreadExecutionPolicy.started(started, "one", "").getValue("one").images)
        assertEquals("turn-one", ThreadExecutionPolicy.started(started, "one", "").getValue("one").turnId)
        assertThrows(IllegalStateException::class.java) {
            ThreadExecutionPolicy.reserve(started, "one", emptyList())
        }
    }

    @Test fun completingUnknownThreadCannotStopOtherExecutions() {
        val active = ThreadExecutionPolicy.reserve(emptyMap(), "one", emptyList())
        assertEquals(active, ThreadExecutionPolicy.finish(active, "unknown"))
    }
}
