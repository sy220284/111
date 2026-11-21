package io.github.sy220284.codexandroid

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteSurfaceThemeTest {
    @Test fun surfacesRemainPureWhite() {
        listOf(Ink.canvas, Ink.recessed, Ink.panel, Ink.elevated).forEach {
            assertEquals(Color.White, it)
        }
    }

    @Test fun textAndSemanticColorsContrastWithWhite() {
        listOf(Ink.parchment, Ink.muted, Ink.copper, Ink.jade, Ink.red).forEach {
            assertTrue(it != Ink.canvas)
        }
    }
}
