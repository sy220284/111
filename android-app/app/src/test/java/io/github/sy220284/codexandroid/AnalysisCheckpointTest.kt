package io.github.sy220284.codexandroid

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AnalysisCheckpointTest {
    private val scope = AnalysisCheckpoint.identity("https://workspace", "secret")
    @Test fun completedSegmentsSurviveRestartAndStayWithTheirConversationAndCredential() {
        val directory = Files.createTempDirectory("analysis-results").toFile()
        try {
            val first = AnalysisCheckpoint("A", scope, "/media.mp4", "SegmentedVideo", "说明", listOf("第一段"), 3)
            val second = first.copy(owner = "B", parts = listOf("其他会话"))
            AnalysisCheckpointStore(directory).save(first, false)
            AnalysisCheckpointStore(directory).save(second, false)
            val reopened = AnalysisCheckpointStore(directory)
            assertEquals(first, reopened.read("A", scope, false))
            assertEquals(second, reopened.read("B", scope, false))
            assertNull(reopened.read("A", AnalysisCheckpoint.identity("https://workspace", "new-key"), false))
            assertNull(reopened.read("A", scope, true))
            assertFalse(first.complete)
            assertEquals(first, AnalysisCheckpoint.decode(first.encode()))
        } finally { directory.deleteRecursively() }
    }
    @Test fun screenReviewCursorNeverDropsANewerCompletedObservation() {
        val directory = Files.createTempDirectory("screen-results").toFile()
        try {
            val store = AnalysisCheckpointStore(directory)
            val reviewed = AnalysisCheckpoint("A", scope, "screen", "screen", "", listOf("1", "2"), 2)
            store.save(reviewed.copy(parts = listOf("1", "2", "3"), total = 3), true)
            store.save(reviewed.copy(applied = 2), true)
            val recovered = store.read("A", scope, true)!!
            assertEquals(listOf("1", "2", "3"), recovered.parts)
            assertEquals(2, recovered.applied)
            store.remove("A", scope, true)
            assertNull(store.read("A", scope, true))
        } finally { directory.deleteRecursively() }
    }
    @Test fun independentWritersKeepBothNewObservationsAndReviewProgress() {
        val directory = Files.createTempDirectory("concurrent-results").toFile()
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(30) { index ->
                val owner = "thread-$index"
                val record = AnalysisCheckpoint(owner, scope, "screen", "screen", "", listOf("1", "2"), 2)
                AnalysisCheckpointStore(directory).save(record, true)
                val start = java.util.concurrent.CountDownLatch(1)
                val review = workers.submit { start.await(); AnalysisCheckpointStore(directory).save(record.copy(applied = 2), true) }
                val observation = workers.submit { start.await(); AnalysisCheckpointStore(directory).save(
                    record.copy(parts = listOf("1", "2", "3"), total = 3), true) }
                start.countDown()
                review.get(5, java.util.concurrent.TimeUnit.SECONDS)
                observation.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val recovered = AnalysisCheckpointStore(directory).read(owner, scope, true)!!
                assertEquals(listOf("1", "2", "3"), recovered.parts)
                assertEquals(2, recovered.applied)
            }
        } finally { workers.shutdownNow(); directory.deleteRecursively() }
    }
    @Test fun damagedRecordFailsExplicitlyAndIsNotOverwrittenByRead() {
        val directory = Files.createTempDirectory("bad-results").toFile()
        try {
            val store = AnalysisCheckpointStore(directory)
            store.save(AnalysisCheckpoint("A", scope, "source", "mode", ""), false)
            val file = directory.listFiles()!!.single()
            file.writeText("damaged")
            assertThrows(Exception::class.java) { store.read("A", scope, false) }
            assertEquals("damaged", file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun allTwelveFullNativeResponsesSurviveAndStaleWritesCannotEraseThem() {
        val directory = Files.createTempDirectory("full-media-results").toFile()
        try {
            val store = AnalysisCheckpointStore(directory)
            val first = AnalysisCheckpoint("A", scope, "/media.mp4", "Understand", "question",
                listOf("a".repeat(64_000)), 12, sourceTurn = "turn")
            val completed = first.copy(parts = List(12) { "a".repeat(64_000) }, applied = 12)
            store.save(first, false)
            store.save(completed, false)
            store.save(first, false)
            assertEquals(completed, store.read("A", scope, false))
            val other = first.copy(question = "different")
            store.save(other, false)
            assertEquals(2, store.listMedia("A", scope).size)
            assertEquals(completed, store.readMedia("A", scope, completed.identity))
            assertEquals(other, store.readMedia("A", scope, other.identity))
            store.removeMedia(other)
            assertEquals(completed, store.read("A", scope, false))
        } finally { directory.deleteRecursively() }
    }
}
