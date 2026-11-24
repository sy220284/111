package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
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
