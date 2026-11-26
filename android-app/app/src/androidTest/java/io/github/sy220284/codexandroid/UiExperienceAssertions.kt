package io.github.sy220284.codexandroid

import android.view.WindowInsets
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals

internal class UiExperienceAssertions(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>
) {
    private val density: Float get() = rule.activity.resources.displayMetrics.density

    fun bounds(tag: String, minWidthDp: Float = 0f, minHeightDp: Float = 0f): Rect {
        val node = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val rect = node.boundsInWindow
        val epsilon = 2f
        assertTrue("$tag has invalid geometry: $rect", rect.width > 0 && rect.height > 0)
        assertTrue("$tag width is ${rect.width / density}dp, expected >= ${minWidthDp}dp",
            rect.width + epsilon >= minWidthDp * density)
        assertTrue("$tag height is ${rect.height / density}dp, expected >= ${minHeightDp}dp",
            rect.height + epsilon >= minHeightDp * density)
        assertTrue("$tag is clipped: bounds=$rect layout=${node.size}",
            rect.width + epsilon >= node.size.width && rect.height + epsilon >= node.size.height)
        return rect
    }

    fun nonOverlap(vararg tags: String) {
        val items = tags.map { it to bounds(it) }
        for (i in items.indices) for (j in i + 1 until items.size) {
            val a = items[i]; val b = items[j]
            val x = minOf(a.second.right, b.second.right) - maxOf(a.second.left, b.second.left)
            val y = minOf(a.second.bottom, b.second.bottom) - maxOf(a.second.top, b.second.top)
            assertTrue("${a.first} overlaps ${b.first}: $x x $y", x <= 2f || y <= 2f)
        }
    }

    fun alignedCenters(a: String, b: String, toleranceDp: Float = 3f) {
        val first = bounds(a); val second = bounds(b)
        assertTrue("$a and $b center alignment differs",
            kotlin.math.abs(first.center.y - second.center.y) <= toleranceDp * density)
    }

    fun visibleAboveIme(vararg tags: String) {
        var limit = 0
        rule.runOnUiThread {
            val activity = rule.activity
            val insets = requireNotNull(activity.window.decorView.rootWindowInsets)
            check(insets.isVisible(WindowInsets.Type.ime())) { "IME is not visible" }
            limit = activity.windowManager.currentWindowMetrics.bounds.height() -
                insets.getInsets(WindowInsets.Type.ime()).bottom
        }
        for (tag in tags) {
            val rect = bounds(tag)
            assertTrue("$tag is covered by IME: ${rect.bottom} > $limit", rect.bottom <= limit + 2)
        }
    }

    fun untruncatedText(tag: String, expected: String) {
        val node = rule.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(expected)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("Missing text layout result for $tag", action(results))
        }
        assertTrue("Missing text layout for $tag", results.isNotEmpty())
        for (result in results) {
            assertTrue("$tag text overflows: $expected", !result.hasVisualOverflow)
            assertTrue("$tag is ellipsized: $expected",
                (0 until result.lineCount).none { result.isLineEllipsized(it) })
        }
    }
}
