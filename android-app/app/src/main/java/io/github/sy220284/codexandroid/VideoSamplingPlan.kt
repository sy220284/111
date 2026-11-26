package io.github.sy220284.codexandroid

/**
 * Pure, deterministic sampling budget. Baseline coverage is independent of scene-change ranking.
 * A transition never consumes the whole budget or removes coverage of quiet intervals.
 */
internal object VideoSamplingPlan {
    const val MAX_DURATION_MS = 600_000L
    const val SEGMENT_MS = 60_000L
    const val MAX_PROBES = 240
    const val MAX_FRAMES_PER_SEGMENT = 12
    const val MOTION_THRESHOLD = 0.075

    data class Window(val startMs: Long, val endMs: Long)
    data class Probe(val timeMs: Long, val change: Double)
    data class Segment(val window: Window, val times: List<Long>)
    data class Gop(val startUs: Long, val frames: Int)

    /** Decode sequentially only when measured GOP structure predicts a clear win.
     * Short-GOP sparse requests retain indexed seeks. The estimate affects only
     * the decoder choice, never the baseline sampling budget. */
    fun preferSequential(gops: List<Gop>, probesMs: List<Long>, durationUs: Long): Boolean {
        if (gops.isEmpty() || probesMs.size < 4 || durationUs <= 0 ||
            gops.any { it.frames <= 0 } || gops.zipWithNext().any { (a, b) -> a.startUs >= b.startUs }) return false
        val frames = gops.sumOf { it.frames.toLong() }
        var random = 0L
        var index = 0
        for (probe in probesMs) {
            val time = probe * 1000
            while (index + 1 < gops.size && gops[index + 1].startUs <= time) index++
            val gop = gops[index]
            val next = gops.getOrNull(index + 1)?.startUs ?: durationUs
            val span = (next - gop.startUs).coerceAtLeast(1)
            val elapsed = (time - gop.startUs).coerceIn(0, span)
            random += 1 + (gop.frames - 1L) * elapsed / span
        }
        return random >= frames * 2
    }

    fun windows(duration: Long): List<Window> {
        require(duration in 1..MAX_DURATION_MS) { "视频时长上限10分钟" }
        return generateSequence(0L) { it + SEGMENT_MS }
            .takeWhile { it < duration }.map { Window(it, minOf(duration, it + SEGMENT_MS)) }.toList()
    }

    fun probeTimes(duration: Long): List<Long> {
        windows(duration)
        val step = maxOf(400L, (duration + MAX_PROBES - 2) / (MAX_PROBES - 1))
        return (generateSequence(0L) { it + step }.takeWhile { it < duration }.toList() + (duration - 1)).distinct()
    }

    fun plan(duration: Long, probes: List<Probe>): List<Segment> = windows(duration).map { window ->
        val span = window.endMs - window.startMs
        val baselineCount = ((span + 9_999) / 10_000).toInt().coerceIn(3, 6)
        val coverage = (0 until baselineCount).map { i ->
            window.startMs + (span - 1) * i / (baselineCount - 1)
        }.toMutableSet()
        val changes = probes.filter { it.timeMs in window.startMs until window.endMs && it.change >= MOTION_THRESHOLD }
            .sortedWith(compareByDescending<Probe> { it.change }.thenBy { it.timeMs })
        val step = maxOf(400L, (duration + MAX_PROBES - 2) / (MAX_PROBES - 1))
        for (event in changes) {
            if (coverage.size >= MAX_FRAMES_PER_SEGMENT) break
            // Sample both sides of major transitions, then refine immediately around them.
            val candidates = listOf(event.timeMs - step, event.timeMs, event.timeMs - step / 2, event.timeMs + step / 2)
            for (time in candidates) {
                if (coverage.size >= MAX_FRAMES_PER_SEGMENT) break
                val t = time.coerceIn(window.startMs, window.endMs - 1)
                if (coverage.none { kotlin.math.abs(it - t) < minOf(150L, step / 4) }) coverage.add(t)
            }
        }
        Segment(window, coverage.sorted())
    }

    /** Color-aware normalized mean pixel difference (0..1). Includes motion and abrupt cuts. */
    fun change(previous: IntArray, current: IntArray): Double {
        require(previous.size == current.size && current.isNotEmpty())
        var sum = 0L
        for (i in current.indices) {
            val a = previous[i]
            val b = current[i]
            sum += kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255))
            sum += kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255))
            sum += kotlin.math.abs((a and 255) - (b and 255))
        }
        return sum / (current.size * 765.0)
    }
}
