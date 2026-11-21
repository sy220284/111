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
        if (rule.onAllNodesWithTag("bottom-settings").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("bottom-settings").performClick()
        } else {
            rule.onNodeWithContentDescription("返回上一页").performClick()
            rule.onNodeWithTag("settings-search").assertExists()
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("settings-search").assertCountEquals(1)
        val search = rule.onNodeWithTag("settings-search")
        search.performTextClearance()
        search.performTextInput(pages.first { it.id == id }.title)
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-" + id))
        rule.onNodeWithTag("settings-route-" + id).performClick()
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
        rule.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        capture("chat-toolbar-android35.png")
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithText("添加附件").assertExists()
        rule.onNodeWithTag("attachment-pick-image").assertExists()
        rule.onNodeWithTag("quick-terminal").assertDoesNotExist()
        captureOverlay("attachment-sheet-android35.png")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithText("工作区工具").assertExists()
        rule.onNodeWithTag("quick-terminal").assertExists()
        captureOverlay("tools-sheet-android35.png")
    }

    @Test fun statusButtonHasStableMeaningAndSeparatesActions() {
        rule.onNodeWithTag("engine-status-open").performClick()
        rule.onNodeWithText("运行状态").assertExists()
        rule.onNodeWithText("查看诊断和日志").assertExists()
        captureOverlay("engine-status-android35.png")
    }

    @Test fun modelChoiceUsesOneSharedBottomSheet() {
        openDrawerRoute("chat")
        rule.onNodeWithText("默认模型").performClick()
        rule.onNodeWithText("选择运行模型").assertExists()
        captureOverlay("model-sheet-android35.png")
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

    @Test fun modeSheetRespectsRealEngineCapability() {
        openDrawerRoute("chat")
        rule.onNodeWithText("执行", substring = false).performClick()
        rule.onNodeWithText("选择协作方式").assertExists()
        captureOverlay("mode-sheet-android35.png")
        val available = rule.onAllNodesWithText("先制定计划").fetchSemanticsNodes().isNotEmpty()
        if (available) {
            rule.onNodeWithText("先制定计划").performClick()
            rule.onNodeWithText("先计划").assertExists()
        } else {
            rule.onNodeWithText("检查计划模式支持情况").assertExists()
        }
    }

    @Test fun settingsGroupsAndInlineAppearanceAndUpdate() {
        openDrawerRoute("settings")
        rule.onNodeWithText("账户与模型").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-update"))
        rule.onNodeWithTag("settings-update").assertExists()
        rule.onNodeWithTag("settings-update-toggle").performClick()
        rule.onNodeWithTag("update-check").assertExists()
        capture("settings-hub-android35.png")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-search"))
        rule.onNodeWithTag("settings-search").performTextInput("外观")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-appearance"))
        rule.onNodeWithTag("settings-route-appearance").performClick()
        rule.onNodeWithText("柔和阴影").assertExists()
        rule.onNodeWithText("减少动效").assertExists()
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

    @Test fun featureCatalogRetainsAllTwentyEightLegacyRoutes() {
        val widthDp = rule.activity.resources.configuration.screenWidthDp
        assertTrue("宽度应为 360/393/411dp，实际 $widthDp",
            widthDp in setOf(360, 393, 411))
        assertEquals(29, pages.size)
        assertEquals(28, UiFeatureCatalog.legacyRoutes.size)
        assertEquals(pages.filter { it.id != "settings" }.map { it.id }.toSet(),
            UiFeatureCatalog.legacyRoutes.toSet())
        assertEquals(4, NavigationPolicy.featureGroups.size)
        assertEquals(15, NavigationPolicy.settingsRoutes.size)
        openSettingRoute("plugins")
        rule.onNodeWithTag("screen-title").assertTextEquals("插件")
        openSettingRoute("config")
        rule.onNodeWithTag("screen-title").assertTextEquals("引擎配置")
        openSettingRoute("diagnostics")
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun threadLibraryExposesServerBackedManagementLayout() {
        openDrawerRoute("threads")
        rule.onNodeWithText("会话资料库").assertExists()
        rule.onNodeWithText("活跃", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performClick()
        rule.onNodeWithText("搜索会话标题").assertExists()
        rule.onNodeWithText("全文搜索").assertExists()
        rule.onNodeWithText("新建任务").assertExists()
        capture("thread-library-android35.png")
        rule.onNodeWithText("全文搜索").performClick()
        rule.onNodeWithText("搜索历史正文").assertExists()
        capture("thread-full-search-android35.png")
    }

    @Test fun accountMetricsUsesSharedUsageAndLimitsTabs() {
        openSettingRoute("usage")
        rule.onNodeWithText("账号使用概况").assertExists()
        capture("account-usage-android35.png")
        rule.onNodeWithText("额度", substring = false).performClick()
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
        // 创建目录后由业务页面自动收起键盘，并在此验收该交互。
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-menu-" + name))
        rule.onNodeWithTag("workspace-menu-" + name).performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("删除", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("删除", substring = false).performClick()
        rule.onNodeWithText("确认删除文件夹").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithTag("workspace-menu-" + name).assertExists()
        rule.onNodeWithTag("workspace-menu-" + name).performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("删除", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
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
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-diagnostics"))
        rule.onNodeWithTag("settings-route-diagnostics").performClick()
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
        // Diff 需要活动会话；未选择时先进入可选会话资料库。
        rule.onNodeWithTag("screen-title").assertTextEquals("会话历史")
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
