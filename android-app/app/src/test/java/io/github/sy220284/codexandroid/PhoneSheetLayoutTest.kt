package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSheetLayoutTest {
    @Test fun goalPanelFitsPortraitPhoneContent() {
        assertEquals(0.86f, PhoneSheetLayout.goalFraction(false), 0.0001f)
        assertEquals(0.94f, PhoneSheetLayout.goalFraction(true), 0.0001f)
    }

    @Test fun queuePanelGrowsWithContent() {
        assertEquals(0.72f, PhoneSheetLayout.queueFraction(0), 0.0001f)
        assertEquals(0.72f, PhoneSheetLayout.queueFraction(2), 0.0001f)
        assertEquals(0.86f, PhoneSheetLayout.queueFraction(5), 0.0001f)
        assertEquals(0.94f, PhoneSheetLayout.queueFraction(20), 0.0001f)
    }
}
