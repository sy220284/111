package io.github.sy220284.codexandroid

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** A normal Activity, with the platform animation clock and physical touch events. */
class UiMotionPerformanceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun findVisible(root: AccessibilityNodeInfo, label: String, clickable: Boolean, textOnly: Boolean): Rect? {
        val matches = root.isVisibleToUser && (root.text?.toString() == label ||
            (!textOnly && root.contentDescription?.toString() == label))
        if (matches) {
            var action = root
            var owned = false
            try {
                while (clickable && !action.isClickable) {
                    val parent = action.parent ?: return null
                    if (owned) action.recycle()
                    action = parent
                    owned = true
                }
                val rect = Rect()
                action.getBoundsInScreen(rect)
                if (action.isVisibleToUser && !rect.isEmpty) return rect
            } finally { if (owned) action.recycle() }
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            try {
                val result = findVisible(child, label, clickable, textOnly)
                if (result != null) return result
            } finally { child.recycle() }
        }
        return null
    }

    private fun waitForLabel(label: String, clickable: Boolean = false, textOnly: Boolean = false): Rect {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    val result = findVisible(root, label, clickable, textOnly)
                    if (result != null) return result
                } finally { root.recycle() }
            }
            Thread.sleep(100)
        }
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val evidence = StringBuilder()
        fun describe(node: AccessibilityNodeInfo, depth: Int) {
            evidence.append(" ".repeat(depth)).append(node.className).append(" text=").append(node.text)
                .append(" description=").append(node.contentDescription).append(" visible=").append(node.isVisibleToUser)
                .append(" clickable=").append(node.isClickable).append('\n')
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { describe(child, depth + 1) } finally { child.recycle() }
            }
        }
        if (root != null) try { describe(root, 0) } finally { root.recycle() }
        File(folder, "ui-motion-failure.txt").writeText(evidence.toString())
        captureFailureDisplayScreenshot("ui-motion-failure.png")
        throw AssertionError("Actual accessibility label did not become visible: $label; $evidence")
    }

    private fun physicalTap(label: String) {
        val rect = waitForLabel(label, clickable = true)
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally { event.recycle() }
        }
        Thread.sleep(650)
    }

    private fun visibleLabel(label: String): Rect? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return try { findVisible(root, label, false, true) } finally { root.recycle() }
    }

    private fun physicalSwipe(x: Float, from: Float, to: Float) {
        val start = SystemClock.uptimeMillis()
        for (step in 0..16) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val y = from + (to - from) * step / 16
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(event) }
            finally { event.recycle() }
            Thread.sleep(24)
        }
        Thread.sleep(650)
    }

    @Test fun realNavigationFramesAndRepeatedGestures() {
        val samples = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val phases = Collections.synchronizedList(mutableListOf<List<Long>>())
        val navigationStage = java.util.concurrent.atomic.AtomicReference("startup")
        val stageSamples = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val phaseIds = listOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
            FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION,
            FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION, FrameMetrics.GPU_DURATION)
        val phaseNames = listOf("input", "animation", "layout_measure", "draw", "sync", "command_issue", "swap_buffers", "gpu")
        val lost = java.util.concurrent.atomic.AtomicInteger()
        val resources = PerformanceResourceSampler(instrumentation.targetContext) { navigationStage.get() }
        val arguments = InstrumentationRegistry.getArguments()
        val physical = arguments.getString("physicalPerformance") == "true"
        val seconds = arguments.getString("streamSeconds")?.toInt() ?: 8
        val rate = arguments.getString("streamRate")?.toInt() ?: 25
        require(seconds in 8..1800 && rate in 10..100)
        var streamUpdates = 0
        var pressureElapsed = 0L
        val thread = HandlerThread("ci-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
            if (total > 0) {
                samples.add(total to deadline)
                phases.add(phaseIds.map { metrics.getMetric(it) })
                stageSamples.add(navigationStage.get() to total)
            }
            lost.addAndGet(dropped)
        }
        val originalMotion = UiPreferences.reducedMotion.value
        var refreshRate = 60f
        var rendererPipelines = emptyList<String>()
        var attached = false
        val intent = android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            scenario.onActivity {
                UiPreferences.setReducedMotion(false)
                refreshRate = it.display?.refreshRate ?: 60f
            }
            waitForLabel("打开侧边导航", clickable = true)
            Thread.sleep(700)
            val rendererDump = instrumentation.uiAutomation.executeShellCommand(
                "dumpsys gfxinfo " + instrumentation.targetContext.packageName)
            rendererPipelines = android.os.ParcelFileDescriptor.AutoCloseInputStream(rendererDump)
                .bufferedReader().use { reader ->
                    reader.readLines().map { it.trim() }.filter { it.startsWith("Pipeline=") }.distinct()
                }
            scenario.onActivity {
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                attached = true
            }
            resources.start()
            repeat(4) {
                navigationStage.set("drawer_open")
                physicalTap("打开侧边导航")
                navigationStage.set("drawer_close_settings_entrance")
                physicalTap("设置")
                waitForLabel("设置", textOnly = true)
                navigationStage.set("settings_back_chat_entrance")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                Thread.sleep(650)
                waitForLabel("打开侧边导航", clickable = true)
                waitForLabel("发送给 Codex")
            }
            val history = (0 until 32).map { i ->
                TimelineItem("motion-history-$i", "agentMessage", "滚动阅读记录 $i", "completed", turnId = "motion-turn-$i")
            }
            val tail = TimelineItem("motion-tail", "agentMessage", "流式阶段末尾", "inProgress", turnId = "motion-live")
            navigationStage.set("reply_stream_initial")
            scenario.onActivity { (it.application as CodexApplication).session.timeline.value = history + tail }
            val tailRect = waitForLabel("流式阶段末尾", textOnly = true)
            navigationStage.set("reply_scroll_drag")
            val distance = (tailRect.centerY() * .35f).coerceAtLeast(120f)
            physicalSwipe(tailRect.exactCenterX(), tailRect.exactCenterY() - distance, tailRect.exactCenterY() - 16f)
            waitForLabel("返回最新进度", clickable = true)
            val anchor = history.map { it.text }.firstNotNullOfOrNull { label ->
                visibleLabel(label)?.let { label to it }
            } ?: error("长回复滚动后没有可见历史锚点")
            navigationStage.set("reply_stream_while_reading")
            repeat(10) { n ->
                scenario.onActivity { (it.application as CodexApplication).session.timeline.value =
                    history + tail.copy(text = "流式阶段末尾 " + "新增内容 ".repeat(n + 1)) }
                Thread.sleep(100)
            }
            val after = waitForLabel(anchor.first, textOnly = true)
            assertTrue("实际滚动阅读位置偏移：" + anchor.second + " -> " + after,
                kotlin.math.abs(after.top - anchor.second.top) <= 2)
            navigationStage.set("reply_scroll_return")
            physicalTap("返回最新进度")
            navigationStage.set("reply_stream_finish")
            scenario.onActivity { (it.application as CodexApplication).session.timeline.value =
                history + tail.copy(text = "流式最终成果", status = "completed") }
            waitForLabel("流式最终成果", textOnly = true)

            // Native immutable append snapshots, real UI rendering, large retained
            // history. The test producer is deliberately separate from the UI thread.
            val largeHistory = IndexedTimeline.from((0 until 5000).map { n ->
                TimelineItem("pressure-history-$n", "agentMessage", "历史记录 $n", "completed", turnId = "pressure-history-$n")
            })
            fun newTail(id: Int) = TimelineItem("pressure-$id", "agentMessage", "", "streaming",
                evidence = JSONObject().put("id", "pressure-$id").put("type", "agentMessage").put("text", "").toString(),
                turnId = "pressure-turn-$id", phase = "final_answer")
            var pressureTail = newTail(0).appendNative("text", "压力测试起点\n\n", 0, "streaming")
            val initial = largeHistory.put(pressureTail)
            navigationStage.set("stream_pressure")
            scenario.onActivity { (it.application as CodexApplication).session.timeline.value = initial }
            waitForLabel("压力测试起点", textOnly = true)
            val start = SystemClock.elapsedRealtime()
            val interval = 1000.0 / rate
            val deadline = start + seconds * 1000L
            val addition = ("正文持续增长，保留原文及 Unicode 😀。".repeat(18) + "\n\n")
            var turnLength = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                if (turnLength + addition.length > 500000) {
                    pressureTail = newTail(streamUpdates)
                    turnLength = 0
                }
                pressureTail = pressureTail.appendNative("text", addition, 0, "streaming")
                turnLength += addition.length
                val next = largeHistory.put(pressureTail)
                scenario.onActivity { (it.application as CodexApplication).session.timeline.value = next }
                streamUpdates++
                val nextAt = start + (streamUpdates * interval).toLong()
                val wait = nextAt - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            pressureElapsed = SystemClock.elapsedRealtime() - start
            val finalPressure = largeHistory.put(pressureTail.appendNative("text", "压力测试结束\n\n", 0, "completed").withStatus("completed"))
            scenario.onActivity { (it.application as CodexApplication).session.timeline.value = finalPressure }
            waitForLabel("压力测试结束", textOnly = true)
        } finally {
            resources.stop()
            scenario.onActivity {
                if (attached) it.window.removeOnFrameMetricsAvailableListener(listener)
                (it.application as CodexApplication).session.timeline.value = emptyList()
                UiPreferences.setReducedMotion(originalMotion)
            }
            scenario.close()
            thread.quitSafely()
            thread.join(3000L)
        }
        val frames = synchronized(samples) { samples.toList() }
        val values = frames.map { it.first }
        val sorted = values.sorted()
        val p95 = if (sorted.isEmpty()) Double.POSITIVE_INFINITY else
            sorted[(kotlin.math.ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
        val budget = 1_000_000_000.0 / refreshRate
        val missed = frames.count { (duration, deadline) -> duration > (if (deadline > 0) deadline.toDouble() else budget) }
        val verySlow = values.count { it > 100_000_000L }
        val report = JSONObject().put("scenario", "drawer_settings_back_4_rounds")
            .put("refresh_hz", refreshRate.toDouble()).put("frames", values.size)
            .put("renderer_pipelines", JSONArray(rendererPipelines))
            .put("deadline_misses", missed).put("very_slow_frames", verySlow)
            .put("listener_dropped", lost.get())
            .put("p95_ms", if (p95.isFinite()) p95 else JSONObject.NULL)
            .put("frame_ns", JSONArray(values)).put("deadline_ns", JSONArray(frames.map { it.second }))
        // Diagnose CPU/UI versus renderer costs without weakening the original gate.
        val phaseSamples = synchronized(phases) { phases.toList() }
        val phaseReport = JSONObject()
        phaseNames.forEachIndexed { index, name ->
            val sortedPhase = phaseSamples.map { it[index] }.filter { it >= 0 }.sorted()
            if (sortedPhase.isNotEmpty()) phaseReport.put(name, JSONObject()
                .put("samples", sortedPhase.size)
                .put("p95_ms", sortedPhase[(kotlin.math.ceil(sortedPhase.size * .95).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0))
        }
        report.put("phase_metrics", phaseReport)
        // Callback-time stage labels are diagnostic only. All frames still
        // determine the original, unchanged overall performance gate.
        val stageReport = JSONObject()
        synchronized(stageSamples) { stageSamples.toList() }.groupBy({ it.first }, { it.second })
            .forEach { (stage, durations) ->
                val ordered = durations.sorted()
                stageReport.put(stage, JSONObject()
                    .put("frames", ordered.size)
                    .put("p95_ms", ordered[(kotlin.math.ceil(ordered.size * .95).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0)
                    .put("very_slow_frames", ordered.count { it > 100_000_000L }))
            }
        report.put("navigation_stage_metrics", stageReport)
        report.put("long_reply_scroll_anchor_verified", true)
        report.put("stream_pressure", JSONObject().put("history_items", 5000)
            .put("target_updates_per_second", rate).put("updates", streamUpdates)
            .put("elapsed_ms", pressureElapsed)
            .put("observed_updates_per_second", streamUpdates * 1000.0 / pressureElapsed.coerceAtLeast(1)))
        report.put("resources", resources.report())
        report.put("device_class", if (physical) "physical_arm64" else "emulator")
        val pressure = stageReport.optJSONObject("stream_pressure")
        assertTrue("没有长回复压力帧", pressure != null && pressure.optInt("frames") > 0 && streamUpdates > 0)
        assertTrue("缺少资源采样", report.getJSONObject("resources").getJSONArray("samples").length() >= 2)
        for (stage in listOf("reply_scroll_drag", "reply_scroll_return")) {
            val measured = stageReport.optJSONObject(stage)
            assertTrue("缺少长回复实际动效帧：" + stage, measured != null && measured.optInt("frames") > 0)
        }
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(folder, "ui-motion-performance.json").writeText(report.toString(2))
        InstrumentationRegistry.getArguments().getString("expectedRenderer")?.let { expected ->
            assertTrue("Unexpected actual renderer: $report", expected in rendererPipelines)
        }
        assertTrue("Insufficient rendered frame evidence: $report", values.size >= 12 && lost.get() == 0)
        assertTrue("Severe rendering regression: $report", p95 <= 120.0 &&
            verySlow.toDouble() / values.size <= .15)
        if (physical) {
            val evidence = report.getJSONObject("resources")
            assertTrue("Physical run requires actual unplugged energy or charge-counter evidence",
                evidence.optBoolean("unplugged") && evidence.optDouble("consumed_mwh", -1.0) > 0.0)
            assertTrue("Physical stream frame budget exceeded: $pressure",
                pressure!!.getDouble("p95_ms") <= budget / 1_000_000.0)
        }
    }
}
