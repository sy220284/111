package io.github.sy220284.codexandroid

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.json.JSONArray
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
        // 从侧栏进入设置，子页返回后等待搜索框出现；不依赖重复的底栏入口。
        if (rule.onAllNodesWithContentDescription("返回上一页").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription("返回上一页").performClick()
        } else {
            openDrawerRoute("settings")
        }
        // 等待真实目标页面出现在语义树中，避免 Compose 重组尚未生效时断言旧标题。
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("settings-search").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("screen-title").assertTextEquals("设置")
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
        rule.onNodeWithTag("quick-terminal").assertExists()
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

    @Test fun workspaceToolBackNavigatesToWorkspaceRatherThanSettings() {
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-terminal").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
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
        rule.onNodeWithText("查看账号授权与安全设置").assertExists()
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
        openDrawerRoute("files")
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

    @Test fun desktopStyleSidebarIsTheOnlyPrimaryNavigation() {
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("工作台", substring = false).assertExists()
        rule.onNodeWithText("工作区", substring = false).assertExists()
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("任务对话")
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        openDrawerRoute("threads")
        rule.onNodeWithTag("screen-title").assertTextEquals("会话历史")
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("诊断")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-diagnostics"))
        rule.onNodeWithTag("settings-route-diagnostics").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun accountSessionsExperimentHasClearOfflineState() {
        openSettingRoute("account")
        rule.onNodeWithText("账号与工作区切换（实验）").performScrollTo().performClick()
        rule.onNodeWithText("账号与工作区切换", substring = false).assertExists()
        val connected = rule.onAllNodesWithText("刷新账号与工作区", substring = false)
            .fetchSemanticsNodes().isNotEmpty()
        if (connected) rule.onNodeWithText("刷新账号与工作区").assertExists()
        else rule.onNodeWithText("连接本机内核").assertExists()
        captureOverlay("account-sessions-android35.png")
    }

    @Test fun pluginDiscoveryCanOpenSearchWithoutInventedResults() {
        openSettingRoute("plugins")
        rule.onNodeWithTag("plugins-tab-catalog").performClick()
        rule.onNodeWithText("搜索插件（实验）").assertExists()
        rule.onNodeWithText("插件名称或关键字").assertExists()
        capture("plugin-search-android35.png")
    }

    @Test fun installedPluginManagementAndCatalogStayReachable() {
        openSettingRoute("plugins")
        rule.onNodeWithTag("plugins-tab-installed").assertExists()
        rule.onNodeWithTag("plugins-tab-catalog").performClick()
        rule.onNodeWithText("插件源", substring = false).assertExists()
        capture("plugins-android35.png")
    }

    @Test fun secondarySettingsPagesHaveActualDeviceScreenshots() {
        // Every captured screen comes from the current Android Activity and real navigation.
        val routeScreens = listOf(
            "providers", "skills", "apps", "mcp", "hooks",
            "environments", "security", "config", "diagnostics", "queue"
        )
        routeScreens.forEach { route ->
            openSettingRoute(route)
            rule.onNodeWithTag("screen-title")
                .assertTextEquals(pages.first { it.id == route }.title)
            rule.waitForIdle()
            capture("audit-" + route + "-android35.png")
        }
    }

    @Test fun approvalsAndChildEditorsHaveReadableStates() {
        openDrawerRoute("approvals")
        rule.onNodeWithTag("screen-title").assertTextEquals("待审批操作")
        capture("audit-approvals-android35.png")

        openSettingRoute("providers")
        rule.onNodeWithText("添加自定义服务商").performScrollTo().performClick()
        rule.onNodeWithText("服务地址（HTTP / HTTPS）").assertExists()
        capture("audit-provider-editor-android35.png")

        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithText("设置值（JSON）").assertExists()
        capture("audit-config-editor-android35.png")

        openSettingRoute("environments")
        rule.onNodeWithText("添加外部执行环境").performScrollTo().performClick()
        rule.onNodeWithText("执行服务 WebSocket URL").assertExists()
        capture("audit-environment-editor-android35.png")
    }

    @Test fun expandedSecurityAndAboutViewsAreCaptured() {
        openSettingRoute("security")
        rule.onNodeWithText("可用权限方案").performScrollTo()
        capture("audit-security-profiles-android35.png")

        openSettingRoute("about")
        rule.onNodeWithText("关于应用").assertExists()
        captureOverlay("audit-about-android35.png")
    }

    @Test fun permissionApprovalExplainsActualScope() {
        val vm = ViewModelProvider(rule.activity)[CodexViewModel::class.java]
        openDrawerRoute("approvals")
        val permissions = JSONObject()
            .put("network", JSONObject().put("enabled", true))
            .put("fileSystem", JSONObject().put("write",
                JSONArray().put("/data/user/0/io.github.sy220284.codexandroid/files/projects")))
        rule.runOnUiThread {
            vm.bridge.prompts.value = listOf(
                ServerPrompt("ci-approval", "item/permissions/requestApproval",
                    JSONObject().put("permissions", permissions))
            )
        }
        rule.onNodeWithText("允许访问网络", substring = true).assertExists()
        rule.onNodeWithText("修改：本机工作区", substring = true).assertExists()
        capture("audit-permission-request-android35.png")
        rule.runOnUiThread { vm.bridge.prompts.value = emptyList() }
    }

    @Test fun taskSpecificSheetsAreReachableAndReadable() {
        val vm = ViewModelProvider(rule.activity)[CodexViewModel::class.java]
        rule.runOnUiThread {
            vm.activeThread.value = "ci-selected-session"
            vm.running.value = false
        }
        openDrawerRoute("chat")
        rule.onNodeWithContentDescription("会话工具").performClick()
        rule.onNodeWithText("复制会话分支").assertExists()
        captureOverlay("audit-thread-context-android35.png")
        rule.onNodeWithText("回合历史与回退").performClick()
        rule.waitForIdle()
        captureOverlay("audit-thread-history-transition-android35.png")
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("查看完整时间线（实验）").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("查看完整时间线（实验）").assertExists()
        captureOverlay("audit-thread-history-android35.png")
        rule.onNodeWithText("查看完整时间线（实验）").performClick()
        rule.onNodeWithText("会话时间线").assertExists()
        captureOverlay("audit-advanced-timeline-android35.png")
        rule.onNodeWithContentDescription("返回回合历史").performClick()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("会话工具").performClick()
        rule.onNodeWithText("会话关联资料").performClick()
        rule.onNodeWithText("添加文本备注").assertExists()
        captureOverlay("audit-thread-attachments-android35.png")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-review").performClick()
        rule.onNodeWithText("审查范围").assertExists()
        captureOverlay("audit-review-sheet-android35.png")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-goals").performClick()
        rule.onNodeWithText("设置新目标").assertExists()
        captureOverlay("audit-goals-sheet-android35.png")
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
        captureVerifiedDisplayScreenshot(fileName)
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
