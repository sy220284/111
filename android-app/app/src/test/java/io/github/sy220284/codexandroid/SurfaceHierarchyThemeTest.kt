package io.github.sy220284.codexandroid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceHierarchyThemeTest {
    private fun inTheme(dark: Boolean, check: () -> Unit) {
        val original = UiPreferences.darkMode.value
        try {
            UiPreferences.darkMode.value = dark
            check()
        } finally {
            UiPreferences.darkMode.value = original
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }

    @Test fun lightCanvasAndPanelStayWhiteWhileReadingAndOverlaySurfacesAreDistinct() = inTheme(false) {
        assertEquals(Color.White, Ink.canvas)
        assertEquals(Color.White, Ink.panel)
        assertEquals(Color(0xFFF6F7F8), Ink.recessed)
        assertEquals(Color(0xFFFCFBFA), Ink.elevated)
        assertTrue(Ink.recessed.luminance() < Ink.elevated.luminance())
        assertTrue(Ink.elevated.luminance() < Ink.canvas.luminance())
    }

    @Test fun darkSurfaceHierarchyIsDistinct() = inTheme(true) {
        assertTrue(Ink.recessed.luminance() < Ink.canvas.luminance())
        assertTrue(Ink.canvas.luminance() < Ink.panel.luminance())
        assertTrue(Ink.panel.luminance() < Ink.elevated.luminance())
    }

    @Test fun readableTextAndInteractiveBoundariesOnEverySurface() {
        for (dark in listOf(false, true)) inTheme(dark) {
            for (background in listOf(Ink.canvas, Ink.recessed, Ink.panel, Ink.elevated)) {
                for (foreground in listOf(Ink.parchment, Ink.muted, Ink.copper, Ink.jade, Ink.red, Ink.warning)) {
                    assertTrue("文本对比度：dark=$dark foreground=$foreground background=$background",
                        contrast(foreground, background) >= 4.5f)
                }
                assertTrue("控件边界：dark=$dark background=$background",
                    contrast(Ink.controlBorder, background) >= 3f)
            }
        }
    }
}
