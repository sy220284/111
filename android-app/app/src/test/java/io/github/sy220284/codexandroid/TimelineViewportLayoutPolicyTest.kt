package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class TimelineViewportLayoutPolicyTest {
    @Test fun visibilityChangesDoNotRequestTailFollow() {
        assertFalse(TimelineViewportPolicy.tailResized(100, -1))
        assertFalse(TimelineViewportPolicy.tailResized(-1, 100))
        assertFalse(TimelineViewportPolicy.tailResized(100, 100))
        assertTrue(TimelineViewportPolicy.tailResized(100, 180))
        assertTrue(TimelineViewportPolicy.tailResized(180, 100))
    }

    @Test fun targetMustMatchActualMeasuredList() {
        assertFalse(TimelineViewportPolicy.measuredTargetReady(36, 0, 800))
        assertFalse(TimelineViewportPolicy.measuredTargetReady(36, 36, 800))
        assertFalse(TimelineViewportPolicy.measuredTargetReady(36, 37, 0))
        assertTrue(TimelineViewportPolicy.measuredTargetReady(36, 37, 800))
        assertFalse(TimelineViewportPolicy.measuredTargetReady(-1, 0, 800))
    }
}
