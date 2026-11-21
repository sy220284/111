package io.github.sy220284.codexandroid

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 参考布局验收：侧栏只出现常用入口，所有原有功能归入设置或快捷菜单。 */
class UiNavigationInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun openDrawerRoute(id: String) {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-" + id).performClick()
    }

    private fun openSettingRoute(id: String) {
        rule.onNodeWithTag("bottom-settings").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("settings-search").assertCountEquals(1)
        val search = rule.onNodeWithTag("settings-search")
        search.performTextClearance()
        search.performTextInput(pages.first { it.id == id }.title)
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-" + id))
        rule.onNodeWithTag("settings-" + id).performClick()
    }

    @Test fun drawerOpensAndNavigatesToConversation() {
        rule.onNodeWithText("继续你的工程").assertIsDisplayed()
        capture("home-android35.png")
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("任务对话").assertExists()
        rule.onNodeWithTag("nav-settings").assertExists()
        rule.onNodeWithTag("nav-plugins").assertDoesNotExist()
        capture("sidebar-android35.png")
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithText("新的工作会话").assertExists()
        rule.onNodeWithText("今天想做什么？").assertExists()
        capture("chat-android35.png")
    }

    @Test fun chatKeepsStopAndSendControlsReachable() {
        openDrawerRoute("chat")
        rule.onNodeWithText("停止").assertExists()
        rule.onNodeWithText("发送给 Codex").assertExists()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
    }

    @Test fun narrowConversationToolbarKeepsPrimaryActionsVisible() {
        openDrawerRoute("chat")
        rule.onNodeWithText("默认模型").assertIsDisplayed()
        rule.onNodeWithText("执行", substring = false).assertIsDisplayed()
        rule.onNodeWithContentDescription("查看内核运行诊断").assertIsDisplayed()
        rule.onNodeWithContentDescription("添加附件与工具").assertIsDisplayed()
        capture("chat-toolbar-android35.png")
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithText("快捷工具").assertExists()
        rule.onNodeWithText("从相册或文件中选择图片").assertExists()
        rule.onNodeWithTag("quick-terminal").assertExists()
        captureOverlay("tools-sheet-android35.png")
    }

    @Test fun diffReaderShowsFileFirstSummaryAndReviewAction() {
        openSettingRoute("diff")
        rule.onNodeWithText("本次改动").assertExists()
        rule.onNodeWithText("代码审查").assertExists()
        rule.onNodeWithText("暂无可展示的代码差异").assertExists()
        capture("diff-reader-android35.png")
    }

    @Test fun androidBackReturnsToPreviousScreen() {
        openDrawerRoute("chat")
        rule.onNodeWithText("新的工作会话").assertExists()
        rule.waitForIdle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithText("继续你的工程").assertExists()
    }

    @Test fun modeSheetOffersTwoExplicitActions() {
        openDrawerRoute("chat")
        rule.onNodeWithText("执行", substring = false).performClick()
        rule.onNodeWithText("选择协作方式").assertExists()
        captureOverlay("mode-sheet-android35.png")
        rule.onNodeWithText("先制定计划").performClick()
        rule.onNodeWithText("先计划").assertExists()
    }

    @Test fun settingsGroupsAndAppearancePage() {
        openDrawerRoute("settings")
        rule.onNodeWithText("账户与模型").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-update"))
        rule.onNodeWithTag("settings-update").assertExists()
        rule.onNodeWithTag("update-check").assertExists()
        capture("settings-hub-android35.png")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("扩展能力"))
        rule.onNodeWithText("扩展能力").assertIsDisplayed()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-search"))
        rule.onNodeWithTag("settings-search").performTextInput("外观")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-appearance"))
        rule.onNodeWithTag("settings-appearance").performClick()
        rule.onNodeWithText("柔雾层次材质").assertExists()
        rule.onNodeWithText("交互预览").assertExists()
        capture("appearance-android35.png")
    }

    @Test fun terminalExecutionShowsReadableConfirmation() {
        openSettingRoute("terminal")
        rule.onNodeWithText("Shell 命令").performTextInput("pwd")
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
        rule.onNodeWithText("确认执行命令").assertDoesNotExist()
    }

    @Test fun accountPageExposesNarrowScreenDeviceLoginActions() {
        openSettingRoute("account")
        rule.onNodeWithText("选择登录方式").assertExists()
        rule.onNodeWithText("设备码登录").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("浏览器登录").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("查看协议数据").assertExists()
        rule.onNodeWithText("刷新账号状态").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("设备码登录").performScrollTo()
        capture("account-android35.png")
        rule.onNodeWithText("使用量", substring = false).performScrollTo().performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("使用量")
    }

    @Test fun allTwentyEightFeatureRoutesStayReachableAfterNavigationRefactor() {
        val widthDp = rule.activity.resources.configuration.screenWidthDp
        assertTrue("验收宽度限定为 360/393/411dp，实际为 $widthDp",
            widthDp in setOf(360, 393, 411))
        assertEquals(29, pages.size) // 原有 28 页 + 设置聚合页
        assertEquals(23, NavigationPolicy.settingsRoutes.size)
        val actualOriginal = NavigationPolicy.sidebarRoutes.filter { it != "settings" } +
            NavigationPolicy.settingsRoutes
        assertEquals(28, actualOriginal.distinct().size)
        assertEquals(pages.filter { it.id != "settings" }.map { it.id }.toSet(),
            actualOriginal.toSet())
        for (id in NavigationPolicy.settingsRoutes) {
            openSettingRoute(id)
            rule.onNodeWithTag("screen-title").assertTextEquals(
                pages.first { it.id == id }.title)
        }
        rule.onNodeWithTag("bottom-settings").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("设置")
        for (id in NavigationPolicy.sidebarRoutes) {
            openDrawerRoute(id)
            rule.onNodeWithTag("screen-title").assertTextEquals(
                pages.first { it.id == id }.title)
        }
    }

    @Test fun threadLibraryExposesServerBackedManagementLayout() {
        openDrawerRoute("threads")
        rule.onNodeWithText("会话资料库").assertExists()
        rule.onNodeWithText("活跃", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performClick()
        rule.onNodeWithText("搜索会话标题").assertExists()
        rule.onNodeWithText("新建任务").assertExists()
        capture("thread-library-android35.png")
    }

    @Test fun accountMetricsHavePurposeBuiltLayouts() {
        openSettingRoute("usage")
        rule.onNodeWithText("账号使用概况").assertExists()
        capture("account-usage-android35.png")
        openSettingRoute("limits")
        rule.onNodeWithText("额度窗口").assertExists()
        capture("account-limits-android35.png")
    }

    @Test fun workspaceFoldersCanBeCreatedAndRemovedWithExplicitConfirmation() {
        val name = "ui-folder-" + System.currentTimeMillis()
        rule.onNodeWithTag("bottom-files").performClick()
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

    @Test fun frequentBottomDestinationsAndSettingsSearchStayReachable() {
        rule.onNodeWithTag("main-bottom-navigation").assertExists()
        rule.onNodeWithTag("bottom-chat").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("任务对话")
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        rule.onNodeWithTag("bottom-threads").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("会话历史")
        rule.onNodeWithTag("bottom-settings").performClick()
        rule.onNodeWithTag("settings-search").performTextInput("诊断")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-diagnostics"))
        rule.onNodeWithTag("settings-diagnostics").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun installedPluginManagementAndCatalogStayReachable() {
        openSettingRoute("plugins")
        rule.onNodeWithTag("plugins-tab-installed").assertExists()
        rule.onNodeWithTag("plugins-tab-catalog").performClick()
        rule.onNodeWithText("插件源", substring = false).assertExists()
        capture("plugins-android35.png")
    }

    @Test fun toolbarQuickActionsCanOpenProjectTools() {
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-terminal").assertExists()
        rule.onNodeWithTag("quick-diff").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("代码差异")
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
