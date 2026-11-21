package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Verifies a real Material 3 overlay, including explicit dismiss without side effects. */
class CodexOverlayInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun evidenceSheetShowsReadableResultAndCloses() {
        rule.setContent {
            CodexTheme {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    CodexEvidenceSheet(
                        title = "命令执行 · 执行依据",
                        payload = "{\"exitCode\":0,\"stdout\":\"done\"}",
                        onDismiss = { visible = false }
                    )
                }
            }
        }
        rule.onNodeWithText("命令执行 · 执行依据").assertExists()
        rule.waitForIdle()
        captureVerifiedDisplayScreenshot("evidence-sheet-android35.png")
        rule.onNodeWithText("执行成功").assertExists()
        rule.onNodeWithText("done").assertExists()
        rule.onNodeWithText("exitCode", substring = true).assertDoesNotExist()
        rule.onNodeWithText("复制执行详情").performClick()
        rule.onNodeWithText("关闭").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("命令执行 · 执行依据").assertDoesNotExist()
    }
}
