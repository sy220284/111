package io.github.sy220284.codexandroid

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
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
    fun chatKeepsStopAndSendControlsReachable() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("任务对话").performClick()
        rule.onNodeWithText("停止").assertExists()
        rule.onNodeWithText("发送给 Codex").assertExists()
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
        captureOverlay("mode-sheet-android35.png")
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
        capture("appearance-android35.png")
    }

    @Test
    fun terminalExecutionShowsReadableConfirmation() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("设备沙箱")
        rule.onNodeWithText("终端", substring = false).performClick()
        rule.onNodeWithText("Shell 命令").performTextInput("pwd")
        // Close the IME before pressing the command CTA, as a user would on a
        // keyboard-covered device. The full native APK uses system IME animations.
        rule.runOnUiThread {
            val input = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            rule.activity.currentFocus?.clearFocus()
        }
        rule.waitForIdle()
        rule.onNodeWithText("执行命令").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("确认执行命令").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("确认执行命令").assertExists()
        rule.onNodeWithText("确认执行").assertExists()
        captureOverlay("terminal-confirm-android35.png")
        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertTrue("取消后确认窗口仍存在",
            rule.onAllNodesWithText("确认执行命令").fetchSemanticsNodes().isEmpty())
    }

    /** Capture the whole Android display, including dialog and bottom-sheet windows. */
    @Test
    fun accountPageExposesNarrowScreenDeviceLoginActions() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("账号登录")
        rule.onNode(hasText("账号登录") and !hasSetTextAction()).performClick()
        rule.onNodeWithText("选择登录方式").assertExists()
        rule.onNodeWithText("设备码登录").assertIsDisplayed()
        rule.onNodeWithText("浏览器登录").assertIsDisplayed()
        rule.onNodeWithText("查看协议数据").assertExists()
        rule.onNodeWithText("刷新账号状态").assertIsDisplayed()
        capture("account-android35.png")
    }

    @Test
    fun allTwentyEightSidebarDestinationsOpenWithoutCrashing() {
        // CI verifies every sidebar destination at 360, 393 and 411dp phone widths.
        // The header must match the active route, and the emulator must use an approved width.
        val widthDp = rule.activity.resources.configuration.screenWidthDp
        org.junit.Assert.assertTrue(
            "UI 验收宽度应为 360/393/411dp，实际为 ${widthDp}dp",
            widthDp in setOf(360, 393, 411)
        )
        rule.onNodeWithTag("screen-title").assertTextEquals("总览")
        for (destination in pages.filter { it.id != "home" }) {
            rule.onNodeWithContentDescription("打开侧边导航").performClick()
            val search = rule.onNodeWithTag("navigation-search")
            search.performTextClearance()
            search.performTextInput(destination.title)
            rule.waitForIdle()
            rule.onNodeWithTag("nav-${destination.id}").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("screen-title").assertTextEquals(destination.title)
        }
    }

    private fun captureOverlay(fileName: String) {
        rule.waitForIdle()
        val screen = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Android display screenshot unavailable: $fileName" }
        saveScreenshot(fileName, screen)
    }

    private fun saveScreenshot(fileName: String, screenshot: Bitmap) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(app.getExternalFilesDir(null)) {
            "Android instrumentation external files directory unavailable"
        }
        File(directory, fileName).outputStream().use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Failed to encode screen capture: $fileName"
            }
        }
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
