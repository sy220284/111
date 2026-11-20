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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
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
    fun narrowConversationToolbarKeepsPrimaryActionsVisible() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithText("默认模型").assertIsDisplayed()
        rule.onNodeWithText("执行", substring = false).assertIsDisplayed()
        rule.onNodeWithText("内核状态").assertIsDisplayed()
        rule.onNodeWithText("添加图片").assertIsDisplayed()
        capture("chat-toolbar-android35.png")
    }

    @Test
    fun diffReaderShowsFileFirstSummaryAndReviewAction() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        // The sidebar intentionally expands only one group. Filter the target
        // before selecting it, as an actual user would.
        rule.onNodeWithTag("navigation-search").performTextInput("代码差异")
        rule.onNodeWithTag("nav-diff").performClick()
        rule.onNodeWithText("本次改动").assertExists()
        rule.onNodeWithText("代码审查").assertExists()
        rule.onNodeWithText("暂无可展示的代码差异").assertExists()
        capture("diff-reader-android35.png")
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
        rule.onNodeWithText("设备码登录").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("浏览器登录").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("查看协议数据").assertExists()
        rule.onNodeWithText("刷新账号状态").performScrollTo().assertIsDisplayed()
        // Preserve a clear screenshot of the top-level login choices.
        rule.onNodeWithText("设备码登录").performScrollTo()
        capture("account-android35.png")
    }

    @Test
    fun allTwentyEightSidebarDestinationsOpenWithoutCrashing() {
        // Exercise every real sidebar entry on a 320dp Android device, not just
        // a small hand-picked subset. The header is bound to the active route.
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

    @Test
    fun threadLibraryExposesServerBackedManagementLayout() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-threads").performClick()
        rule.onNodeWithText("会话资料库").assertExists()
        rule.onNodeWithText("活跃", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performClick()
        rule.onNodeWithText("搜索会话标题").assertExists()
        rule.onNodeWithText("新建任务").assertExists()
        capture("thread-library-android35.png")
    }

    @Test
    fun accountMetricsHavePurposeBuiltLayouts() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        val search = rule.onNodeWithTag("navigation-search")
        search.performTextClearance()
        search.performTextInput("使用量")
        rule.onNodeWithTag("nav-usage").performClick()
        rule.onNodeWithText("账号使用概况").assertExists()
        capture("account-usage-android35.png")

        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        val nextSearch = rule.onNodeWithTag("navigation-search")
        nextSearch.performTextClearance()
        nextSearch.performTextInput("使用限额")
        rule.onNodeWithTag("nav-limits").performClick()
        rule.onNodeWithText("额度窗口").assertExists()
        capture("account-limits-android35.png")
    }

    @Test
    fun workspaceFoldersCanBeCreatedAndRemovedWithExplicitConfirmation() {
        val name = "ui-folder-" + System.currentTimeMillis()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        val search = rule.onNodeWithTag("navigation-search")
        search.performTextClearance()
        search.performTextInput("工作区文件")
        rule.onNodeWithTag("nav-files").performClick()
        rule.onNodeWithTag("workspace-list").performScrollToNode(
            hasTestTag("workspace-folder-mode"))
        rule.onNodeWithTag("workspace-folder-mode").performClick()
        rule.onNodeWithTag("workspace-new-name").performTextInput(name)
        rule.onNodeWithTag("workspace-list").performScrollToNode(
            hasTestTag("workspace-create"))
        rule.onNodeWithTag("workspace-create").performClick()
        rule.onNodeWithTag("workspace-list").performScrollToNode(
            hasTestTag("workspace-menu-" + name))
        rule.onNodeWithTag("workspace-menu-" + name).assertExists()
        capture("workspace-files-android35.png")

        rule.onNodeWithTag("workspace-menu-" + name).performClick()
        rule.onNodeWithText("删除", substring = false).performClick()
        rule.onNodeWithText("确认删除文件夹").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithTag("workspace-menu-" + name).assertExists()
        rule.onNodeWithTag("workspace-menu-" + name).performClick()
        rule.onNodeWithText("删除", substring = false).performClick()
        rule.onNodeWithText("永久删除").performClick()
        rule.waitUntil(10_000L) {
            rule.onAllNodesWithText(name, substring = false).fetchSemanticsNodes().isEmpty()
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
