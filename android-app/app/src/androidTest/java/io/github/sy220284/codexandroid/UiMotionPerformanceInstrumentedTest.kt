package io.github.sy220284.codexandroid

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections

class UiMotionPerformanceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun physicalTap(node: SemanticsNodeInteraction) {
        val center = node.assertIsDisplayed().fetchSemanticsNode().boundsInWindow.center
        val screen = IntArray(2)
        val window = IntArray(2)
        rule.runOnUiThread {
            rule.activity.window.decorView.getLocationOnScreen(screen)
            rule.activity.window.decorView.getLocationInWindow(window)
        }
        val x = center.x + screen[0] - window[0]
        val y = center.y + screen[1] - window[1]
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
            } finally { event.recycle() }
        }
        // Real time passes with no Compose clock advancement or waitForIdle during animation.
        Thread.sleep(650)
        rule.waitForIdle()
    }

    @Test fun realNavigationFramesAndRepeatedGestures() {
        rule.waitUntil(20_000L) {
            rule.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes().size == 1
        }
        val samples = Collections.synchronizedList(mutableListOf<Long>())
        val deadlines = Collections.synchronizedList(mutableListOf<Long>())
        val lost = java.util.concurrent.atomic.AtomicInteger()
        val thread = HandlerThread("ci-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
            if (total > 0) {
                samples.add(total)
                deadlines.add(deadline)
            }
            lost.addAndGet(dropped)
        }
        val originalMotion = UiPreferences.reducedMotion.value
        var refreshRate = 60f
        try {
            rule.runOnUiThread {
                UiPreferences.setReducedMotion(false)
                refreshRate = rule.activity.display?.refreshRate ?: 60f
            }
            rule.waitForIdle()
            rule.runOnUiThread {
                rule.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            }
            repeat(4) {
                physicalTap(rule.onNodeWithContentDescription("打开侧边导航"))
                rule.onNodeWithTag("sidebar-drawer").assertIsDisplayed()
                physicalTap(rule.onNodeWithTag("nav-settings"))
                rule.onNodeWithTag("settings-list").assertIsDisplayed()
                physicalTap(rule.onNodeWithContentDescription("返回上一页"))
                rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
            }
        } finally {
            rule.runOnUiThread {
                rule.activity.window.removeOnFrameMetricsAvailableListener(listener)
                UiPreferences.setReducedMotion(originalMotion)
            }
            thread.quitSafely()
            thread.join(3000L)
        }
        val values = synchronized(samples) { samples.toList() }
        val limits = synchronized(deadlines) { deadlines.toList() }
        val sorted = values.sorted()
        val p95 = if (sorted.isEmpty()) Double.POSITIVE_INFINITY else
            sorted[(kotlin.math.ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
        val budget = 1_000_000_000.0 / refreshRate
        val missed = values.indices.count { i -> values[i] > (limits.getOrNull(i)?.takeIf { it > 0 }?.toDouble() ?: budget) }
        val verySlow = values.count { it > 100_000_000L }
        val report = JSONObject().put("scenario", "drawer_settings_back_4_rounds")
            .put("refresh_hz", refreshRate.toDouble()).put("frames", values.size)
            .put("deadline_misses", missed).put("very_slow_frames", verySlow)
            .put("listener_dropped", lost.get())
            .put("p95_ms", if (p95.isFinite()) p95 else JSONObject.NULL)
            .put("frame_ns", JSONArray(values)).put("deadline_ns", JSONArray(limits))
        val folder = requireNotNull(rule.activity.getExternalFilesDir(null))
        File(folder, "ui-motion-performance.json").writeText(report.toString(2))
        assertTrue("Insufficient rendered frame evidence: $report", values.size >= 12 && lost.get() == 0)
        assertTrue("Severe emulator rendering regression: $report", p95 <= 120.0 &&
            verySlow.toDouble() / values.size <= .15)
    }
}
