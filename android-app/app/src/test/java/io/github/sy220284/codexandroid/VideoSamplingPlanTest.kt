package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

internal fun createVideoFixture(path: String, audio: Boolean, duration: Long = 2_000) {
    val timeline = VideoTimeline(duration, VideoSamplingPlan.windows(duration).map {
        VideoTimeline.Segment(it.startMs, it.endMs, listOf(it.startMs, it.endMs - 1).distinct(),
            if (audio) it.startMs * 1000 else null)
    })
    File(VideoTimeline.manifest(path)).writeText(timeline.encode())
    LocalMediaAttachmentPolicy.ownedPaths(path).filterNot { it == VideoTimeline.manifest(path) }
        .forEach { File(it).writeText("test") }
}

class VideoSamplingPlanTest {
    @Test fun allDurationsHaveCoverageAndBoundedWork() {
        for (duration in listOf(1L, 400L, 1_000L, 59_999L, 60_000L, 60_001L, 300_000L, 600_000L)) {
            val probes = VideoSamplingPlan.probeTimes(duration)
            assertTrue(probes.size <= VideoSamplingPlan.MAX_PROBES)
            assertEquals(0L, probes.first())
            assertEquals(duration - 1, probes.last())
            val plan = VideoSamplingPlan.plan(duration, probes.map { VideoSamplingPlan.Probe(it, 1.0) })
            assertEquals(0L, plan.first().window.startMs)
            assertEquals(duration, plan.last().window.endMs)
            plan.forEach { segment ->
                assertEquals(segment.window.startMs, segment.times.first())
                assertEquals(segment.window.endMs - 1, segment.times.last())
                assertTrue(segment.times.size <= VideoSamplingPlan.MAX_FRAMES_PER_SEGMENT)
                assertEquals(segment.times.distinct().sorted(), segment.times)
            }
            plan.zipWithNext().forEach { (a, b) -> assertEquals(a.window.endMs, b.window.startMs) }
        }
    }

    @Test fun changingScenesAllocateMoreSamplesWithoutDroppingBaseline() {
        val duration = 60_000L
        val quiet = VideoSamplingPlan.plan(duration, emptyList()).single()
        val active = VideoSamplingPlan.plan(duration, listOf(
            VideoSamplingPlan.Probe(13_000, 0.80),
            VideoSamplingPlan.Probe(35_000, 0.65),
            VideoSamplingPlan.Probe(52_000, 0.10)
        )).single()
        assertTrue(active.times.size > quiet.times.size)
        assertTrue(active.times.containsAll(quiet.times))
        assertTrue(active.times.contains(13_000L))
        assertTrue(active.times.any { it in 12_600..12_999 })
        assertTrue(active.times.size <= 12)
        assertEquals(0.0, VideoSamplingPlan.change(intArrayOf(0xff000000.toInt()), intArrayOf(0xff000000.toInt())), 0.00001)
        assertEquals(1.0, VideoSamplingPlan.change(intArrayOf(0xff000000.toInt()), intArrayOf(0xffffffff.toInt())), 0.00001)
    }

    @Test fun longVideoPreservesOrderedAudioVisualGroupsAndRejectsMissingManifestOrSidecar() {
        val root = Files.createTempDirectory("video-timeline").toFile()
        try {
            val path = File(root, "source.av.mp4").path
            createVideoFixture(path, true, 125_000)
            val timeline = VideoTimeline.read(path)
            assertEquals(3, timeline.segments.size)
            val request = TurnRequestContract.input("", listOf(path))
            assertEquals(10, request.length())
            for (i in 0..2) {
                val base = 1 + i * 3
                assertTrue(request.getJSONObject(base).getString("text").contains("第 ${i + 1} 段"))
                assertEquals(VideoTimeline.sheet(path, i), request.getJSONObject(base + 1).getString("path"))
                assertEquals(VideoTimeline.audio(path, i), request.getJSONObject(base + 2).getString("path"))
            }
            File(VideoTimeline.audio(path, 1)).delete()
            assertThrows(IllegalArgumentException::class.java) { TurnRequestContract.input("", listOf(path)) }
            File(VideoTimeline.manifest(path)).delete()
            assertThrows(IllegalArgumentException::class.java) { TurnRequestContract.input("", listOf(path)) }
            assertTrue(LocalMediaAttachmentPolicy.recoveryPaths(path).contains(VideoTimeline.sheet(path, 2)))
        } finally { root.deleteRecursively() }
    }
}
