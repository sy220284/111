package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/** UI navigation test runs without a Codex binary; backend unavailability is a valid empty state. */
class UiNavigationInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawerOpensAndNavigatesToConversation() {
        rule.onNodeWithText("继续你的工程").assertExists()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("任务对话").assertExists()
        capture("sidebar-android35.png")
        rule.onNodeWithText("任务对话").performClick()
        rule.onNodeWithText("新的工作会话").assertExists()
        rule.onNodeWithText("从一个具体目标开始").assertExists()
        capture("chat-android35.png")
    }


    @Test
    fun androidBackReturnsToPreviousScreen() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("任务对话").performClick()
        rule.onNodeWithText("新的工作会话").assertExists()
        rule.waitForIdle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithText("继续你的工程").assertExists()
    }

    @Test
    fun modeSheetOffersTwoExplicitActions() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("任务对话").performClick()
        rule.onNodeWithText("执行", substring = false).performClick()
        rule.onNodeWithText("选择协作方式").assertExists()
        rule.onNodeWithText("先制定计划").performClick()
        rule.onNodeWithText("先计划").assertExists()
    }

    @Test
    fun sidebarFiltersSectionsAndOpensAppearance() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("外观")
        rule.onNodeWithText("外观与交互").performClick()
        rule.onNodeWithText("柔雾层次材质").assertExists()
        rule.onNodeWithText("交互预览").assertExists()
    }

    @Test
    fun terminalExecutionShowsReadableConfirmation() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("设备沙箱")
        rule.onNodeWithText("终端", substring = false).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("pwd")
        rule.onNodeWithText("执行命令").performClick()
        rule.onNodeWithText("确认执行命令").assertExists()
        rule.onNodeWithText("确认执行").assertExists()
    }

    private fun capture(fileName: String) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(app.getExternalFilesDir(null)) {
            "Android instrumentation external files directory unavailable"
        }
        val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, fileName).outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Failed to encode Compose screenshot: $fileName"
            }
        }
    }
}
