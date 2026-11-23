package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSheetLayoutTest {
    @Test fun goalPanelFitsPortraitPhoneContent() {
        assertEquals(0.68f, PhoneSheetLayout.goalFraction(false), 0.0001f)
        assertEquals(0.82f, PhoneSheetLayout.goalFraction(true), 0.0001f)
    }

    @Test fun queuePanelGrowsWithContent() {
        assertEquals(0.62f, PhoneSheetLayout.queueFraction(0), 0.0001f)
        assertEquals(0.62f, PhoneSheetLayout.queueFraction(2), 0.0001f)
        assertEquals(0.78f, PhoneSheetLayout.queueFraction(5), 0.0001f)
        assertEquals(0.90f, PhoneSheetLayout.queueFraction(20), 0.0001f)
    }
}
