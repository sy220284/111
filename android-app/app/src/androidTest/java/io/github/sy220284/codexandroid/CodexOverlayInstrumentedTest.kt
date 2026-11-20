package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
    fun evidenceSheetShowsRawPayloadAndCloses() {
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(directory, "evidence-sheet-android35.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        rule.onNodeWithText("复制原始记录").performClick()
        rule.onNodeWithText("关闭").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("命令执行 · 执行依据").assertDoesNotExist()
    }
}
