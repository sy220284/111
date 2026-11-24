package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.*

internal fun assertV8ComponentStates(rule: ComposeContentTestRule) {
    var busy by mutableStateOf(false)
    var clicks = 0
    val originalReduced = UiPreferences.reducedMotion.value
    try {
        rule.setContent {
            CodexTheme {
                Column {
                    CodexPrimaryButton(onClick = { clicks++ }, busy = busy,
                        modifier = Modifier.testTag("v8-primary")) { Text("保存设置") }
                    CodexInputField("secret", {}, label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.testTag("v8-secret"))
                }
            }
        }
        val button = rule.onNodeWithTag("v8-primary")
        button.assertIsEnabled()
        val initialWidth = button.fetchSemanticsNode().boundsInRoot.width
        rule.runOnIdle { busy = true }
        button.assertIsNotEnabled()
        assertEquals(initialWidth, button.fetchSemanticsNode().boundsInRoot.width, 1f)
        rule.runOnIdle { UiPreferences.setReducedMotion(true) }
        button.assertIsNotEnabled()
        assertEquals(initialWidth, button.fetchSemanticsNode().boundsInRoot.width, 1f)
        rule.runOnIdle { busy = false }
        button.assertIsEnabled().performClick()
        rule.onNodeWithText("显示").performClick()
        rule.onNodeWithText("隐藏").assertExists().performClick()
        rule.onNodeWithText("显示").assertExists()
        rule.runOnIdle { assertEquals(1, clicks) }
    } finally {
        rule.runOnIdle { UiPreferences.setReducedMotion(originalReduced) }
    }
}

internal fun assertV8ThemeContrast(rule: ComposeContentTestRule) {
    fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
    val original = UiPreferences.darkMode.value
    try {
        rule.setContent { CodexTheme { Text("主题对比度") } }
        for (dark in listOf(false, true)) {
            rule.runOnIdle {
                UiPreferences.setDarkMode(dark)
                for (background in listOf(Ink.canvas, Ink.panel, Ink.recessed, Ink.elevated)) {
                    assertTrue("控件边界：dark=$dark", contrast(Ink.controlBorder, background) >= 3f)
                    assertTrue("正文：dark=$dark", contrast(Ink.parchment, background) >= 4.5f)
                    assertTrue("焦点：dark=$dark", contrast(Ink.copper, background) >= 3f)
                }
            }
        }
    } finally {
        rule.runOnIdle { UiPreferences.setDarkMode(original) }
    }
}

/** Freeze the real Compose clock: only the incoming page is present, displacement
 * converges to its resting position, and reduced motion has no entrance displacement. */
internal fun assertV8RouteMotion(rule: ComposeContentTestRule) {
    var route by mutableStateOf("original")
    var reduced by mutableStateOf(false)
    rule.setContent {
        CodexTheme {
            CodexRouteEntrance(route, reduced) {
                Box(Modifier.padding(top = 32.dp)) {
                    Text(route, modifier = Modifier.testTag("route-" + route))
                }
            }
        }
    }
    val resting = rule.onNodeWithTag("route-original").fetchSemanticsNode().boundsInRoot.top
    rule.mainClock.autoAdvance = false
    try {
        rule.runOnIdle { route = "incoming" }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("route-original").assertDoesNotExist()
        val incoming = rule.onNodeWithTag("route-incoming")
        val start = incoming.fetchSemanticsNode().boundsInRoot.top
        assertTrue("进入动画应有位移：start=$start resting=$resting", start > resting + 1f)
        rule.mainClock.advanceTimeBy(100L)
        val middle = incoming.fetchSemanticsNode().boundsInRoot.top
        assertTrue("中间帧应趋向静止位置：start=$start middle=$middle resting=$resting",
            middle < start && middle >= resting - 1f)
        rule.mainClock.advanceTimeBy(CodexMotion.navigation.toLong() + 32L)
        assertEquals(resting, incoming.fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.runOnIdle { reduced = true; route = "reduced" }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("route-incoming").assertDoesNotExist()
        assertEquals(resting, rule.onNodeWithTag("route-reduced").fetchSemanticsNode().boundsInRoot.top, 1f)
    } finally {
        rule.mainClock.autoAdvance = true
    }
}
