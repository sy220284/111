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
            (!textOnly && root.contentDescription?.toString() == label)) && (!clickable || root.isClickable)
        if (matches) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            if (!rect.isEmpty) return rect
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
        throw AssertionError("Actual accessibility label did not become visible: $label")
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

    @Test fun realNavigationFramesAndRepeatedGestures() {
        val samples = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val lost = java.util.concurrent.atomic.AtomicInteger()
        val thread = HandlerThread("ci-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
            if (total > 0) samples.add(total to deadline)
            lost.addAndGet(dropped)
        }
        val originalMotion = UiPreferences.reducedMotion.value
        var refreshRate = 60f
        var attached = false
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                UiPreferences.setReducedMotion(false)
                refreshRate = it.display?.refreshRate ?: 60f
            }
            waitForLabel("打开侧边导航", clickable = true)
            Thread.sleep(700)
            scenario.onActivity {
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                attached = true
            }
            repeat(4) {
                physicalTap("打开侧边导航")
                physicalTap("设置")
                waitForLabel("设置", textOnly = true)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                Thread.sleep(650)
                waitForLabel("打开侧边导航", clickable = true)
                waitForLabel("发送给 Codex")
            }
        } finally {
            scenario.onActivity {
                if (attached) it.window.removeOnFrameMetricsAvailableListener(listener)
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
            .put("deadline_misses", missed).put("very_slow_frames", verySlow)
            .put("listener_dropped", lost.get())
            .put("p95_ms", if (p95.isFinite()) p95 else JSONObject.NULL)
            .put("frame_ns", JSONArray(values)).put("deadline_ns", JSONArray(frames.map { it.second }))
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(folder, "ui-motion-performance.json").writeText(report.toString(2))
        assertTrue("Insufficient rendered frame evidence: $report", values.size >= 12 && lost.get() == 0)
        assertTrue("Severe emulator rendering regression: $report", p95 <= 120.0 &&
            verySlow.toDouble() / values.size <= .15)
    }
}
