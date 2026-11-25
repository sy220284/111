package io.github.sy220284.codexandroid

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class CodexViewportPolicyTest {
    @Test fun menuFitsWindowAndMeasuredHeaderAcrossWidthsHeightsAndFontScales() {
        for (width in listOf(240f, 320f, 360f, 393f, 411f, 600f))
            for (height in listOf(40f, 120f, 240f, 320f, 480f, 640f, 800f, 915f))
                for (header in listOf(0f, 56f, 72f, 96f, 140f))
                    for (preferred in listOf(248f, 280f)) {
                        val result = CodexViewportPolicy.menu(width, height, header, preferred)
                        assertTrue(result.width > 0 && result.width <= width && result.width <= preferred)
                        assertTrue(result.height > 0 && result.height <= 420f)
                        assertTrue(result.top + result.height <= height)
                        if (height >= header + 48f) assertEquals(header, result.top, 0f)
                        if (height >= 48f) assertTrue(result.height >= 48f)
                    }
    }

    @Test fun realPopupPositionRespectsBudgetAtBothEdgesAndDirections() {
        for (width in listOf(320, 360, 393, 411, 600))
            for (height in listOf(160, 280, 480, 800))
                for (header in listOf(0, 56, 100))
                    for (direction in LayoutDirection.entries) {
                        val budget = CodexViewportPolicy.menu(width.toFloat(), height.toFloat(), header.toFloat(), 280f)
                        val size = IntSize(budget.width.toInt(), budget.height.toInt())
                        val positioner = CodexAnchoredPosition(budget.top.toInt())
                        for (x in listOf(0, width / 2, width - 48))
                            for (y in listOf(0, height / 2, height - 48)) {
                                val anchor = IntRect(x, y, x + 48, y + 48)
                                val pos = positioner.calculatePosition(anchor, IntSize(width, height), direction, size)
                                assertTrue(pos.x >= 0 && pos.x + size.width <= width)
                                assertTrue(pos.y >= budget.top.toInt() && pos.y + size.height <= height)
                            }
                    }
    }

    @Test fun controlsReflowBeforeCombinedFontScaleCrowdsThem() {
        assertTrue(CodexViewportPolicy.fitsActions(320f, 1f, 2, 132f))
        assertFalse(CodexViewportPolicy.fitsActions(320f, 1.5f, 2, 132f))
        assertTrue(CodexViewportPolicy.fitsActions(620f, 2f, 2, 132f))
        assertFalse(CodexViewportPolicy.fitsActions(240f, 1f, 2, 132f))
    }

    @Test fun composerReservesModelAndAllTouchTargetsBeforeReflow() {
        assertFalse(CodexViewportPolicy.composerModelOwnRow(320f, 1f, 3))
        assertTrue(CodexViewportPolicy.composerModelOwnRow(320f, 1f, 4))
        for (width in listOf(240f, 320f, 360f, 393f, 411f, 600f))
            for (scale in listOf(1f, 1.25f, 1.5f, 2f))
                for (icons in 2..4) {
                    val available = width - 48f * icons
                    assertEquals(available < 132f * maxOf(1f, scale),
                        CodexViewportPolicy.composerModelOwnRow(width, scale, icons))
                }
    }

    @Test fun pageSpacingAndWorkAreaFollowActualHeight() {
        assertEquals(12f, CodexViewportPolicy.pageSpacing(320f, 720f, false).horizontal, 0f)
        assertEquals(20f, CodexViewportPolicy.pageSpacing(411f, 915f, false).vertical, 0f)
        assertEquals(12f, CodexViewportPolicy.pageSpacing(411f, 300f, false).vertical, 0f)
        assertTrue(CodexViewportPolicy.compactWorkArea(300f, false, 1f))
        assertTrue(CodexViewportPolicy.compactWorkArea(530f, false, 1.5f))
        assertTrue(CodexViewportPolicy.compactWorkArea(800f, true, 1f))
        assertFalse(CodexViewportPolicy.compactWorkArea(800f, false, 1.5f))
    }

    @Test fun sheetsUseVisibleHeightRatherThanNominalScreenHeight() {
        assertEquals(275.2f, CodexViewportPolicy.sheetHeight(320f, .86f), .01f)
        assertEquals(352f, CodexViewportPolicy.sheetHeight(400f, .88f), .01f)
        assertEquals(640f, CodexViewportPolicy.sheetHeight(640f, 1f), .01f)
    }
}
