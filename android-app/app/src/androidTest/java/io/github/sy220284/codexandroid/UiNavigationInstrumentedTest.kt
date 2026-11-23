package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 参考布局验收：侧栏只出现常用入口，所有原有功能归入设置或快捷菜单。 */
class UiNavigationInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /** Tests share one application-owned Codex session, so each test owns a clean UI fixture. */
    @Before fun resetSessionFixture() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread {
            vm.activeThread.value = null
            vm.activeTurn.value = null
            vm.running.value = false
            vm.threadSwitching.value = false
            vm.timeline.value = emptyList()
            vm.bridge.prompts.value = emptyList()
            vm.composerDrafts.value = emptyMap()
            vm.errors.value = emptyMap()
        }
        rule.waitForIdle()
    }

    /** Send Back to the focused Android window so Dialog/ModalBottomSheet receives it. */
    private fun pressSystemBack() {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun openDrawerRoute(id: String) {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-" + id).performClick()
    }

    private fun openThreadLibrary() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-view-all").performScrollTo().performClick()
        rule.onNodeWithTag("thread-library-sheet").assertExists()
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
        rule.onNodeWithText("从一项任务开始").assertIsDisplayed()
        capture("home-android35.png")
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").assertExists()
        rule.onNodeWithTag("nav-settings").assertExists()
        rule.onNodeWithTag("nav-plugins").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithContentDescription("搜索侧栏会话").performClick()
        rule.onNodeWithTag("sidebar-search").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭会话搜索").performClick()
        capture("sidebar-android35.png")
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithText("今天想做什么？").assertExists()
        capture("chat-android35.png")
    }

    @Test fun chatAndWorkTabsPreserveTheSamePrimaryNavigation() {
        rule.onNodeWithTag("tab-chat").performClick()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("tab-home").performClick()
        rule.onNodeWithText("从一项任务开始").assertExists()
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").assertExists()
        rule.onNodeWithTag("nav-files").assertExists()
        rule.onNodeWithTag("nav-plugins").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-workspace-tools").assertExists()
        rule.onNodeWithTag("sidebar-new-chat").assertExists()
        rule.onNodeWithTag("nav-terminal").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-workspace-tools").performClick()
        rule.onNodeWithTag("quick-terminal").assertExists()
        rule.onNodeWithTag("quick-review").assertExists()
    }

    @Test fun chatKeepsStopAndSendControlsReachable() {
        openDrawerRoute("chat")
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("composer-input").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送给 Codex").assertExists()
        // 空闲时隐藏停止操作，避免把禁用按钮长期占在输入区。
        rule.onNodeWithTag("composer-stop").assertDoesNotExist()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
    }

    @Test fun narrowConversationToolbarKeepsPrimaryActionsVisible() {
        openDrawerRoute("chat")
        rule.onNodeWithText("默认模型").assertIsDisplayed()
        // 模式由真实内核恢复，可能是「执行」或「先计划」；校验实际可交互的按钮。
        rule.onNodeWithTag("conversation-mode-control").assertIsDisplayed()
        rule.onNodeWithContentDescription("查看内核运行诊断").assertIsDisplayed()
        rule.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        capture("chat-toolbar-android35.png")
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithText("添加附件").assertExists()
        rule.onNodeWithTag("attachment-pick-image").assertExists()
        // Attachment entry only selects media; workspace actions are elsewhere.
        rule.onNodeWithTag("quick-terminal").assertDoesNotExist()
        rule.onNodeWithTag("quick-review").assertDoesNotExist()
        rule.onNodeWithTag("quick-goals").assertDoesNotExist()
        captureOverlay("attachment-sheet-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithContentDescription("工作区工具").performClick()
        rule.onNodeWithTag("quick-terminal").assertExists()
        captureOverlay("tools-sheet-android35.png")
    }

    @Test fun statusButtonHasStableMeaningAndSeparatesActions() {
        openDrawerRoute("chat")
        rule.onNodeWithContentDescription("查看内核运行诊断").performClick()
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
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.activeThread.value = "ci-diff-thread" }
        rule.waitForIdle()
        rule.onNodeWithText("检查修改").performClick()
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
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.waitForIdle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithText("从一项任务开始").assertExists()
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
            // 计划模式不可用时按真实核心连接状态给出可操作的恢复入口。
            val canReconnect = rule.onAllNodesWithText("连接运行核心")
                .fetchSemanticsNodes().isNotEmpty()
            val canReload = rule.onAllNodesWithText("检查可用协作方式")
                .fetchSemanticsNodes().isNotEmpty()
            assertTrue("应明确提供重新连接或重新读取能力目录的入口",
                canReconnect || canReload)
        }
    }

    @Test fun taskFirstHomeHasOnePrimaryActionAndUsefulShortcuts() {
        rule.onNodeWithTag("home-task-hero").assertExists()
        rule.onNodeWithTag("home-primary-action").assertExists()
        rule.onNodeWithText("常用工作").assertExists()
        for (id in listOf("files", "threads", "diff", "terminal")) {
            rule.onNodeWithTag("home-shortcut-$id").assertExists()
        }
        rule.onNodeWithTag("home-shortcut-files").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
    }

    @Test fun settingsOverviewAndLocalBackupCanBeFoundByIntent() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-overview").assertExists()
        rule.onNodeWithTag("settings-account-overview").assertExists()
        rule.onNodeWithTag("settings-engine-overview").assertExists()
        rule.onNodeWithTag("settings-search").performTextInput("备份")
        rule.onNodeWithTag("settings-overview").assertDoesNotExist()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-backup"))
        rule.onNodeWithTag("settings-backup").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-search"))
        rule.onNodeWithTag("settings-search").performTextClearance()
        rule.onNodeWithTag("settings-overview").assertExists()
    }

    @Test fun settingsSearchOpensRealAccountUsageAndLimitsPages() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("额度")
        rule.onNodeWithTag("settings-route-limits").assertExists()
        rule.onNodeWithTag("settings-route-usage").assertDoesNotExist()
        rule.onNodeWithTag("settings-route-limits").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("使用限额")
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
        rule.onNodeWithTag("home-shortcut-terminal").performClick()
        rule.onNodeWithTag("terminal-console").assertExists()
        rule.onNodeWithTag("terminal-console-empty").assertExists()
        val output = rule.onNodeWithTag("terminal-console").fetchSemanticsNode().boundsInRoot
        val emptyPanel = rule.onNodeWithTag("terminal-console-empty").fetchSemanticsNode().boundsInRoot
        val commandArea = rule.onNodeWithTag("terminal-command-input").fetchSemanticsNode().boundsInRoot
        assertTrue("终端输出区应位于输入区上方", output.bottom <= commandArea.top)
        assertTrue("空输出面板应填满终端输出区", emptyPanel.height >= output.height - 8f)
        capture("terminal-android35.png")
        rule.onNodeWithText("输入命令").performTextInput("pwd")
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

    @Test fun featureCatalogKeepsUserFacingRoutesOnly() {
        val widthDp = rule.activity.resources.configuration.screenWidthDp
        assertTrue("宽度应为 360/393/411dp，实际 $widthDp",
            widthDp in setOf(360, 393, 411))
        assertEquals(28, pages.size)
        assertEquals(27, UiFeatureCatalog.legacyRoutes.size)
        assertEquals(pages.filter { it.id != "settings" }.map { it.id }.toSet(),
            UiFeatureCatalog.legacyRoutes.toSet())
        assertEquals(4, NavigationPolicy.featureGroups.size)
        assertEquals(14, NavigationPolicy.settingsRoutes.size)
        openSettingRoute("plugins")
        rule.onNodeWithTag("screen-title").assertTextEquals("插件")
        openSettingRoute("config")
        rule.onNodeWithTag("screen-title").assertTextEquals("引擎配置")
        openSettingRoute("diagnostics")
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun threadLibraryExposesServerBackedManagementLayout() {
        openThreadLibrary()
        rule.onNodeWithText("会话资料库").assertExists()
        rule.onNodeWithText("活跃", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performClick()
        rule.onNodeWithText("搜索会话标题").assertExists()
        rule.onNodeWithText("全文搜索").assertExists()
        rule.onNodeWithTag("thread-library-list")
            .performScrollToNode(hasText("新建任务"))
        rule.onNodeWithText("新建任务").assertExists()
        captureOverlay("thread-library-android35.png")
        rule.onNodeWithText("全文搜索").performClick()
        rule.onNodeWithText("搜索历史正文").assertExists()
        captureOverlay("thread-full-search-android35.png")
    }

    @Test fun accountMetricsUsesSharedUsageAndLimitsTabs() {
        openSettingRoute("account")
        rule.onNodeWithText("使用量", substring = false).performScrollTo().performClick()
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
        rule.onNodeWithTag("nav-home").assertExists()
        rule.onNodeWithTag("nav-files").assertExists()
        rule.onNodeWithTag("nav-threads").assertDoesNotExist()
        rule.onNodeWithTag("nav-approvals").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-new-chat").assertExists()
        rule.onNodeWithTag("sidebar-workspace-tools").assertExists()
        rule.onNodeWithTag("nav-terminal").assertDoesNotExist()
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        openThreadLibrary()
        rule.onNodeWithText("会话资料库").assertExists()
        pressSystemBack()
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


    @Test fun settingsSearchSurvivesReturningFromDetailPage() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("诊断")
        rule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("settings-route-diagnostics"))
        rule.onNodeWithTag("settings-route-diagnostics").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("settings-search").assertTextContains("诊断")
        rule.onNodeWithTag("settings-route-diagnostics").assertExists()
    }


    @Test fun settingsSearchFindsActualOptionsByUserWording() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("API Key")
        rule.onNodeWithTag("settings-route-providers").assertExists()
        rule.onNodeWithText("API 密钥和模型服务商").assertExists()
        rule.onNodeWithTag("settings-route-queue").assertDoesNotExist()
    }

    @Test fun settingsSearchDoesNotExposeTaskOperations() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("任务队列")
        rule.onNodeWithText("未找到相关功能").assertExists()
        rule.onNodeWithTag("settings-route-queue").assertDoesNotExist()
        rule.onNodeWithText("功能搜索结果").assertDoesNotExist()
    }

    @Test fun missingSessionKeepsOriginalToolWhenOpeningPicker() {
        openDrawerRoute("files")
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-queue").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-library-sheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("选择会话后继续：任务队列").assertExists()
        pressSystemBack()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
    }

    @Test fun selectingSessionResumesTheOriginalQueueAction() {
        val vm = (rule.activity.application as CodexApplication).session
        openDrawerRoute("files")
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-queue").performClick()
        rule.onNodeWithTag("thread-library-sheet").assertExists()

        // Let the library's initial server query finish before seeding the offline fixture.
        // request() clears the previous result when it starts a refresh.
        val key = ThreadLibraryQuery.key(false, "", "", null)
        rule.waitUntil(10_000L) {
            key !in vm.loading.value &&
                (key in vm.errors.value || key in vm.data.value)
        }
        // Exercise chooseThread's successful already-open callback without a live server.
        // A real server resume invokes the same callback after thread/read and thread/resume.
        rule.runOnUiThread {
            val selectedId = "ci-queue-resume-thread"
            vm.activeThread.value = selectedId
            vm.data.value = vm.data.value + (key to JSONObject().put("data",
                JSONArray().put(JSONObject().put("id", selectedId)
                    .put("name", "继续队列测试会话"))))
        }
        rule.waitForIdle()
        // LazyColumn only composes rows as they scroll into view.
        // Scroll to the keyed row before querying its visible semantics.
        rule.onNodeWithTag("thread-library-list")
            .performScrollToNode(hasTestTag("thread-library-row-ci-queue-resume-thread"))
        rule.onNodeWithText("继续会话").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-sheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        rule.onNodeWithText("任务队列").assertExists()
    }

    @Test fun queueDraftSurvivesClosingAndReopeningSheet() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.activeThread.value = "ci-queue-draft" }
        rule.waitForIdle()
        openDrawerRoute("files")
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-queue").performClick()
        rule.onNodeWithTag("thread-queue-draft").performTextInput("保留未发送的队列内容")
        rule.runOnUiThread {
            val input = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            rule.activity.currentFocus?.clearFocus()
        }
        rule.waitForIdle()
        // A visible close button must work regardless of IME focus or Back delivery.
        rule.onNodeWithTag("thread-queue-close").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-sheet").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-queue").performClick()
        rule.onNodeWithTag("thread-queue-draft").assertTextContains("保留未发送的队列内容")
    }

    @Test fun queueAndFullSearchOpenAsTaskSheets() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("thread-library-sheet").assertExists()
        rule.onNodeWithText("搜索历史正文").assertExists()
        captureOverlay("thread-global-search-android35.png")
        pressSystemBack()

        rule.runOnUiThread { vm.activeThread.value = "ci-queue-thread" }
        openDrawerRoute("files")
        rule.onNodeWithContentDescription("快捷工具").performClick()
        rule.onNodeWithTag("quick-queue").performClick()
        rule.onNodeWithTag("thread-queue-sheet").assertExists()
        rule.onNodeWithText("任务队列").assertExists()
        assertTrue("同一队列请求错误只显示一次",
            rule.onAllNodesWithText("Codex 内核尚未连接").fetchSemanticsNodes().size <= 1)
        captureOverlay("audit-queue-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
    }

    @Test fun secondarySettingsPagesHaveActualDeviceScreenshots() {
        // Every captured screen comes from the current Android Activity and real navigation.
        val routeScreens = listOf(
            "providers", "skills", "apps", "mcp",
            "environments", "security", "config", "diagnostics"
        )
        routeScreens.forEach { route ->
            openSettingRoute(route)
            rule.onNodeWithTag("screen-title")
                .assertTextEquals(pages.first { it.id == route }.title)
            rule.waitForIdle()
            if (route == "apps" || route == "security") {
                // 同页不同接口可能同时断连；用户只看到一条相同原因。
                assertTrue(rule.onAllNodesWithText("Codex 内核尚未连接")
                    .fetchSemanticsNodes().size <= 1)
            }
            capture("audit-" + route + "-android35.png")
        }
    }

    @Test fun approvalsAndChildEditorsHaveReadableStates() {
        val vm = (rule.activity.application as CodexApplication).session
        openDrawerRoute("files")
        rule.runOnUiThread {
            vm.bridge.prompts.value = listOf(ServerPrompt("ci-sheet-approval",
                "item/permissions/requestApproval", JSONObject().put("reason", "检查执行范围")))
        }
        rule.onNodeWithContentDescription("待审批").performClick()
        rule.onNodeWithText("待处理审批").assertExists()
        captureOverlay("audit-approvals-android35.png")
        pressSystemBack()
        rule.runOnUiThread { vm.bridge.prompts.value = emptyList() }

        openSettingRoute("providers")
        rule.onNodeWithText("添加自定义服务商").performScrollTo().performClick()
        rule.onNodeWithText("服务地址（HTTP / HTTPS）").assertExists()
        capture("audit-provider-editor-android35.png")

        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithText("文本值").assertExists()
        rule.onNodeWithText("开关").assertExists()
        rule.onNodeWithText("高级数据").assertExists()
        capture("audit-config-editor-android35.png")

        openSettingRoute("environments")
        rule.onNodeWithTag("environment-remote-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-remote-name").assertExists()
        rule.onNodeWithTag("environment-endpoint").assertExists()
        capture("audit-environment-editor-android35.png")
    }

    @Test fun executionEnvironmentShowsChoiceBeforeConnectionFields() {
        openSettingRoute("environments")
        rule.onNodeWithTag("environment-local-option").assertExists()
        rule.onNodeWithTag("environment-remote-option").assertExists()
        rule.onNodeWithText("已选择本机工作区").assertExists()
        rule.onNodeWithTag("environment-endpoint").assertDoesNotExist()
        rule.onNodeWithText("本机服务标识：local").assertDoesNotExist()

        rule.onNodeWithTag("environment-remote-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-remote-name").assertExists()
        rule.onNodeWithTag("environment-endpoint").assertExists()
        rule.onNodeWithTag("environment-connect").assertIsNotEnabled()
        rule.onNodeWithTag("environment-remote-name").performTextInput("工作电脑")
        rule.onNodeWithTag("environment-endpoint").performTextInput("wss://example.com/exec")
        rule.onNodeWithTag("environment-connect").assertIsEnabled()

        rule.onNodeWithTag("environment-local-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-endpoint").assertDoesNotExist()
        rule.onNodeWithTag("environment-local-option").assertExists()
        rule.onNodeWithTag("environment-remote-option").performClick()
        rule.onNodeWithTag("environment-remote-name").assertTextContains("工作电脑")
        rule.onNodeWithTag("environment-endpoint").assertTextContains("wss://example.com/exec")
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
        val vm = (rule.activity.application as CodexApplication).session
        openDrawerRoute("files")
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
        rule.onNodeWithContentDescription("待审批").performClick()
        rule.onNodeWithText("待处理审批").assertExists()
        rule.onNodeWithText("允许访问网络", substring = true).assertExists()
        rule.onNodeWithText("修改：本机工作区", substring = true).assertExists()
        captureOverlay("audit-permission-request-android35.png")
        rule.runOnUiThread { vm.bridge.prompts.value = emptyList() }
    }

    @Test fun taskSpecificSheetsAreReachableAndReadable() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread {
            vm.activeThread.value = "ci-selected-session"
            vm.running.value = false
        }
        openDrawerRoute("chat")
        rule.onNodeWithContentDescription("会话工具").performClick()
        rule.onNodeWithText("复制会话分支").assertExists()
        captureOverlay("audit-thread-context-android35.png")
        rule.onNodeWithText("回合历史与回退").performScrollTo().performClick()
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
        rule.onNodeWithText("查看完整时间线（实验）").assertExists()
        pressSystemBack()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithContentDescription("会话工具").performClick()
        rule.onNodeWithText("会话关联资料").performScrollTo().performClick()
        rule.onNodeWithText("添加文本备注").assertExists()
        captureOverlay("audit-thread-attachments-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithContentDescription("会话工具").performClick()
        rule.onNodeWithTag("thread-context-review").performScrollTo().performClick()
        rule.onNodeWithText("审查范围").assertExists()
        captureOverlay("audit-review-sheet-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithContentDescription("会话工具").performClick()
        rule.onNodeWithTag("thread-context-goals").performScrollTo().performClick()
        rule.onNodeWithText("设置新目标").assertExists()
        captureOverlay("audit-goals-sheet-android35.png")
    }

    @Test fun sidebarQuickActionsCanOpenProjectTools() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-workspace-tools").performClick()
        rule.onNodeWithTag("quick-terminal").assertExists()
        rule.onNodeWithTag("quick-diff").performClick()
        // 没有活动会话时，直接打开可关闭的会话管理弹层。
        rule.onNodeWithText("会话资料库").assertExists()
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
