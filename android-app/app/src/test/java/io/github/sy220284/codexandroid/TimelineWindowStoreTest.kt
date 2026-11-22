package io.github.sy220284.codexandroid

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TimelineWindowStoreTest {
    @Test fun largeHistoryIsWindowedAndPagedWithoutLosingEntries() = runBlocking {
        val file = File.createTempFile("codex-timeline-", ".jsonl")
        try {
            val store = TimelineWindowStore(file)
            val all = (0 until 2060).map { TimelineItem("step-$it", "user", "记录 $it") }
            val newest = store.replace("thread-1", all)
            assertEquals(480, newest.size)
            assertEquals(1580, store.olderCount.value)
            var visible = newest
            while (store.olderCount.value > 0) visible = store.readEarlier("thread-1") + visible
            assertEquals(all, visible)
            store.clear()
            assertEquals(0, store.olderCount.value)
        } finally { file.delete() }
    }

    @Test fun compactionDoesNotDuplicatePreviouslyLoadedPages() = runBlocking {
        val file = File.createTempFile("codex-timeline-", ".jsonl")
        try {
            val store = TimelineWindowStore(file)
            val initial = (0 until 980).map { TimelineItem("id-$it", "event", "记录") }
            var window = store.replace("thread-1", initial)
            window = store.readEarlier("thread-1") + window
            window += (980 until 1280).map { TimelineItem("id-$it", "event", "新记录") }
            window = store.compact("thread-1", window)
            assertEquals(480, window.size)
            while (store.olderCount.value > 0) window = store.readEarlier("thread-1") + window
            assertEquals((0 until 1280).map { "id-$it" }, window.map { it.id })
        } finally { file.delete() }
    }
}
