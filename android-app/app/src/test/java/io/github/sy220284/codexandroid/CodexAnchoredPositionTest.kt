package io.github.sy220284.codexandroid

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class CodexAnchoredPositionTest {
    @Test fun keyboardViewportKeepsTopDismissAreaOutsideMenu() {
        val position = CodexAnchoredPosition(72).calculatePosition(
            IntRect(12, 300, 60, 348), IntSize(393, 365),
            LayoutDirection.Ltr, IntSize(248, 270))
        assertTrue(position.y >= 72)
        assertTrue(position.y + 270 <= 365)
        assertTrue(position.x >= 0 && position.x + 248 <= 393)
    }

    @Test fun largeMenuStillFitsVisibleWindow() {
        val position = CodexAnchoredPosition(72).calculatePosition(
            IntRect(360, 300, 408, 348), IntSize(411, 350),
            LayoutDirection.Rtl, IntSize(280, 345))
        assertEquals(IntOffset(128, 5), position)
    }

    @Test fun menuWithoutKeyboardCanOpenBelowAnchor() {
        val position = CodexAnchoredPosition().calculatePosition(
            IntRect(12, 80, 60, 128), IntSize(360, 760),
            LayoutDirection.Ltr, IntSize(248, 200))
        assertEquals(IntOffset(12, 128), position)
    }
}
