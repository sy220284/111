package io.github.sy220284.codexandroid

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** Explicit post-release measurement only. No numerical performance acceptance assertions. */
class PostReleasePressureInstrumentedTest {
    @Test fun recordPressureResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("仅通过发布后专项入口运行", arguments.getString("postReleasePressure") == "true")
        val runId = requireNotNull(arguments.getString("pressureRunId"))
        require(runId.matches(Regex("[0-9a-f]{32}")))
        val seconds = arguments.getString("streamSeconds")?.toInt() ?: 1800
        val rate = arguments.getString("streamRate")?.toInt() ?: 100
        require(seconds in 8..1800 && rate in 10..100)
        val historyCount = 5000
        val turnLimit = 500000
        val frames = Collections.synchronizedList(mutableListOf<Triple<Long, Long, Long>>())
        val dropped = java.util.concurrent.atomic.AtomicInteger()
        val stage = java.util.concurrent.atomic.AtomicReference("startup")
        val resources = PerformanceResourceSampler(instrumentation.targetContext) { stage.get() }
        val thread = HandlerThread("post-release-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            if (stage.get() == "stream_pressure") {
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration > 0) frames.add(Triple(duration, metrics.getMetric(FrameMetrics.DEADLINE),
                    metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)))
                dropped.addAndGet(lost)
            }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        var updates = 0
        var maximumTurnLength = 0
        var elapsed = 0L
        var started = 0L
        var refreshRate = 0f
        var attached = false
        val errors = mutableListOf<String>()
        val originalMotion = UiPreferences.reducedMotion.value
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val output = File(folder, "post-release-pressure-$runId.json")
        try {
            val active = ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            scenario = active
            lateinit var publish: (IndexedTimeline) -> Unit
            active.onActivity { activity ->
                UiPreferences.setReducedMotion(false)
                refreshRate = activity.display?.refreshRate ?: 0f
                val timeline = (activity.application as CodexApplication).session.timeline
                publish = { snapshot -> timeline.value = snapshot }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                attached = true
            }
            val history = IndexedTimeline.from((0 until historyCount).map { n ->
                TimelineItem("pressure-history-$n", "agentMessage", "历史记录 $n", "completed", turnId = "pressure-history-$n")
            })
            fun newTail(n: Int) = TimelineItem("pressure-$n", "agentMessage", "", "streaming",
                evidence = JSONObject().put("id", "pressure-$n").put("type", "agentMessage").put("text", "").toString(),
                turnId = "pressure-turn-$n", phase = "final_answer")
            var tail = newTail(0)
            var length = 0
            val addition = "正文持续增长，保留原文及 Unicode 😀。".repeat(18) + "\n\n"
            publish(history.put(tail))
            instrumentation.waitForIdleSync()
            stage.set("stream_pressure")
            resources.start()
            started = SystemClock.elapsedRealtime()
            val deadline = started + seconds * 1000L
            val interval = 1000.0 / rate
            while (SystemClock.elapsedRealtime() < deadline) {
                if (length == turnLimit) { tail = newTail(updates); length = 0 }
                var size = minOf(addition.length, turnLimit - length)
                if (size < addition.length && Character.isHighSurrogate(addition[size - 1])) size--
                // Complete the exact UTF-16 budget without splitting a surrogate pair.
                val delta = if (size == 0) "。" else addition.substring(0, size)
                tail = tail.appendNative("text", delta, 0, "streaming")
                length += delta.length
                maximumTurnLength = maxOf(maximumTurnLength, length)
                publish(history.put(tail))
                updates++
                val wait = started + (updates * interval).toLong() - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            elapsed = SystemClock.elapsedRealtime() - started
            publish(history.put(tail.withStatus("completed")))
            instrumentation.waitForIdleSync()
        } catch (failure: Exception) {
            errors.add(failure.toString())
        } finally {
            if (started > 0L && elapsed == 0L) elapsed = SystemClock.elapsedRealtime() - started
            try { resources.stop() } catch (failure: Exception) { errors.add(failure.toString()) }
            stage.set("finished")
            try {
                scenario?.onActivity { activity ->
                    if (attached) activity.window.removeOnFrameMetricsAvailableListener(listener)
                    (activity.application as CodexApplication).session.timeline.value = emptyList()
                    UiPreferences.setReducedMotion(originalMotion)
                }
            } catch (failure: Exception) { errors.add(failure.toString()) }
            try { scenario?.close() } catch (failure: Exception) { errors.add(failure.toString()) }
            thread.quitSafely()
            thread.join(5000)
            val snapshot = synchronized(frames) { frames.toList() }
            val durations = snapshot.map { it.first }.sorted()
            val timestamps = snapshot.map { it.third }.filter { it > 0 }.distinct().sorted()
            val span = if (timestamps.size > 1) timestamps.last() - timestamps.first() else 0L
            val hz = if (span > 0) (timestamps.size - 1) * 1_000_000_000.0 / span else null
            val p95 = if (durations.isEmpty()) null else durations[(kotlin.math.ceil(durations.size * .95).toInt() - 1)] / 1_000_000.0
            val observedRate = if (elapsed > 0) updates * 1000.0 / elapsed else null
            val loadRatio = observedRate?.div(rate)
            val budget = if (refreshRate > 0) 1000.0 / refreshRate else null
            val resourceReport = resources.report()
            val missing = mutableListOf<String>()
            if (hz == null) missing.add("实际窗口帧时间戳不足")
            if (resourceReport.optInt("pressure_samples") < 2) missing.add("资源采样不足")
            if (resourceReport.isNull("consumed_mwh")) missing.add("充电或设备电量计数器不可用/无可测变化")
            val report = JSONObject().put("run_id", runId)
                .put("purpose", "post_release_observation").put("release_gate", false)
                .put("status", if (errors.isEmpty() && elapsed >= seconds * 1000L && missing.isEmpty()) "recorded" else "partial")
                .put("errors", JSONArray(errors)).put("missing_metrics", JSONArray(missing))
                .put("history_items", historyCount).put("target_turn_utf16", turnLimit)
                .put("max_observed_turn_utf16", maximumTurnLength)
                .put("target_duration_seconds", seconds).put("elapsed_ms", elapsed)
                .put("target_updates_per_second", rate).put("updates", updates)
                .put("observed_updates_per_second", observedRate ?: JSONObject.NULL)
                .put("load_ratio", loadRatio ?: JSONObject.NULL).put("reference_load_ratio", .9)
                .put("reference_load_reached", loadRatio?.let { it >= .9 } ?: JSONObject.NULL)
                .put("refresh_hz", if (refreshRate > 0) refreshRate.toDouble() else JSONObject.NULL)
                .put("observed_window_frame_hz", hz ?: JSONObject.NULL).put("frames", snapshot.size)
                .put("listener_dropped", dropped.get()).put("p95_ms", p95 ?: JSONObject.NULL)
                .put("reference_frame_budget_ms", budget ?: JSONObject.NULL)
                .put("reference_frame_budget_reached", if (p95 != null && budget != null) p95 <= budget else JSONObject.NULL)
                .put("frame_ns", JSONArray(snapshot.map { it.first }))
                .put("deadline_ns", JSONArray(snapshot.map { it.second }))
                .put("intended_vsync_ns", JSONArray(snapshot.map { it.third }))
                .put("resources", resourceReport)
            output.writeText(report.toString(2))
        }
    }
}
