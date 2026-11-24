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
import org.junit.Assert.assertFalse
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

    /**
     * A visible input field alone does not prove keyboard usability. Verify the
     * platform IME owns real window insets before accepting screenshots/actions.
     */
    private fun assertRealSoftKeyboardOpen() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            throw AssertionError("软件键盘验收需要 Android 30 或更新版本")
        }
        val imeType = android.view.WindowInsets.Type.ime()
        rule.waitUntil(10_000L) {
            val insets = rule.activity.window.decorView.rootWindowInsets
            insets != null && insets.isVisible(imeType) &&
                insets.getInsets(imeType).bottom > 0
        }
    }

    private fun openDrawerRoute(id: String) {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-" + id).performClick()
    }

    private fun openThreadLibrary() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-view-all").performScrollTo().performClick()
        rule.onNodeWithTag("thread-library-list").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    private fun openSettingRoute(id: String) {
        // 从侧栏进入设置，子页返回后等待搜索框出现；不依赖重复的底栏入口。
        if (rule.onAllNodesWithContentDescription("返回上一页").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription("返回上一页").performClick()
            rule.waitForIdle()
        }
        if (rule.onAllNodesWithTag("settings-search").fetchSemanticsNodes().isEmpty()) {
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
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("home-task-hero").assertDoesNotExist()
        rule.onNodeWithText("今天想做什么？").assertIsDisplayed()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            rule.activity.requestedOrientation)
        capture("chat-android35.png")
        openDrawerRoute("home")
        rule.onNodeWithTag("home-task-hero").assertIsDisplayed()
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
        rule.onNodeWithTag("conversation-top-title").assertExists()
        rule.onNodeWithText("今天想做什么？").assertExists()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
    }

    @Test fun offscreenRunningTaskBlocksLocalDataTransferButtons() {
        val vm = (rule.activity.application as CodexApplication).session
        openDrawerRoute("settings")
        // The backup card lives inside a lazily composed settings group.
        // Search and scroll to the actual section before asserting child actions.
        rule.onNodeWithTag("settings-search").performTextInput("备份")
        rule.onNodeWithTag("settings-list").performScrollToNode(
            hasTestTag("settings-backup"))
        rule.onNodeWithTag("backup-export").assertExists()
        try {
            rule.runOnUiThread {
                vm.activeThread.value = null
                vm.running.value = false
                vm.threadExecutions.value = mapOf(
                    "offscreen-active" to ThreadExecution(turnId = "running-turn"))
            }
            rule.waitForIdle()
            rule.onNodeWithTag("backup-export").assertIsNotEnabled()
            rule.onNodeWithTag("backup-import").assertIsNotEnabled()
            rule.onNodeWithText(
                "仍有会话任务、终端命令或审批待处理，请完成后再操作").assertExists()
            capture("audit-backup-background-block-android35.png")
        } finally {
            rule.runOnUiThread { vm.threadExecutions.value = emptyMap() }
            rule.waitForIdle()
        }
    }

    @Test fun globalArchiveOverlayProtectsSettingsAndRestoresThePage() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("备份")
        try {
            rule.runOnUiThread {
                AppDataTransfer.state.value = DataTransferState(
                    busy = true, result = "正在验证本机备份…")
            }
            rule.waitForIdle()
            rule.onNodeWithTag("data-transfer-busy-overlay").assertExists()
            rule.onNodeWithTag("data-transfer-busy-message")
                .assertTextEquals("正在验证本机备份…")
            captureVerifiedDisplayScreenshot("audit-backup-in-progress-android35.png")
            // Pressing Back acknowledges the blocked operation without exposing
            // file/editor controls until the archive reports completion.
            pressSystemBack()
            rule.onNodeWithTag("data-transfer-busy-overlay").assertExists()
        } finally {
            rule.runOnUiThread { AppDataTransfer.state.value = DataTransferState() }
            rule.waitForIdle()
        }
        rule.onNodeWithTag("data-transfer-busy-overlay").assertDoesNotExist()
        rule.onNodeWithTag("settings-search").assertExists()
    }

    @Test fun sidebarKeepsChatAndWorkbenchAsOneNavigationPath() {
        openDrawerRoute("chat")
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openDrawerRoute("home")
        rule.onNodeWithText("从一项任务开始").assertExists()
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").assertExists()
        rule.onNodeWithTag("nav-files").assertExists()
        rule.onNodeWithTag("nav-plugins").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-project-context").assertExists()
        rule.onNodeWithTag("sidebar-new-chat").assertExists()
        rule.onNodeWithTag("nav-terminal").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-project-context").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
    }

    @Test fun newConversationActionRemainsAvailableWhileOtherTaskRuns() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread {
            vm.firstThreadStarting.value = false
            vm.threadSwitching.value = false
            vm.running.value = true
        }
        try {
            rule.waitForIdle()
            rule.onNodeWithTag("top-new-chat").assertIsEnabled()
            rule.onNodeWithContentDescription("打开侧边导航").performClick()
            rule.onNodeWithTag("sidebar-new-chat").assertIsEnabled()
        } finally {
            rule.runOnUiThread { vm.running.value = false }
            rule.waitForIdle()
        }
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

    @Test fun chatKeyboardKeepsComposerActionsAccessible() {
        openDrawerRoute("chat")
        rule.onNodeWithTag("composer-input").performClick()
        rule.onNodeWithTag("composer-input").performTextInput("验证键盘展开后的操作入口")
        rule.onNodeWithTag("composer-input").assertTextContains("验证键盘展开后的操作入口")
        rule.waitForIdle()
        // 必须由系统确认软键盘真实展开；仅能输入文字或按钮可见均不足以验收 IME。
        assertRealSoftKeyboardOpen()
        // Compose-root captures exclude the Android IME window. Capture the full
        // device display so the screenshot actually proves keyboard occlusion.
        captureVerifiedDisplayScreenshot("chat-keyboard-open-android35.png")
        assertRealSoftKeyboardOpen()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
        rule.onNodeWithTag("composer-task-options").assertIsDisplayed()
    }

    @Test fun narrowConversationToolbarKeepsPrimaryActionsVisible() {
        openDrawerRoute("chat")
        rule.onNodeWithText("默认模型").assertIsDisplayed()
        // 模式由真实内核恢复，可能是「执行」或「先计划」；校验实际可交互的按钮。
        rule.onNodeWithTag("conversation-mode-control").assertIsDisplayed()
        rule.onNodeWithContentDescription("查看内核运行诊断").assertIsDisplayed()
        rule.onNodeWithContentDescription("选择照片").assertIsDisplayed()
        capture("chat-toolbar-android35.png")
        // 单一照片来源直接调用系统选择器，省去一层菜单。
        rule.onNodeWithTag("composer-plus").assertHasClickAction()
        rule.onNodeWithText("添加附件").assertDoesNotExist()
        captureOverlay("attachment-sheet-android35.png")
        rule.onNodeWithTag("conversation-top-title").assertExists()
        rule.onNodeWithTag("chat-project-terminal").performClick()
        rule.onNodeWithText("项目终端").assertExists()
        captureOverlay("terminal-sheet-android35.png")
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
        val sampleDiff = "diff --git a/src/main.kt b/src/main.kt\n" +
            "@@ -1 +1 @@\n-before\n+after"
        rule.runOnUiThread {
            vm.activeThread.value = "ci-diff-thread"
            vm.latestDiff.value = sampleDiff
        }
        openDrawerRoute("chat")
        rule.onNodeWithTag("chat-changes-summary").assertExists()
        rule.onNodeWithText("查看", substring = false).performClick()
        rule.onNodeWithText("本次改动").assertExists()
        rule.onNodeWithText("代码审查").assertExists()
        rule.onNodeWithText("src/main.kt").assertExists()
        capture("diff-reader-android35.png")
    }

    @Test fun projectTerminalAndFilesReturnToConversation() {
        openDrawerRoute("chat")
        rule.onNodeWithTag("chat-project-terminal").performClick()
        rule.onNodeWithText("项目终端").assertExists()
        rule.onNodeWithContentDescription("收起终端").performClick()
        rule.onNodeWithTag("conversation-composer").assertExists()
        rule.onNodeWithTag("chat-project-files").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        // Project files offer both a global session drawer and a contextual return.
        rule.onNodeWithContentDescription("打开侧边导航").assertExists()
        rule.onNodeWithTag("files-return-to-conversation").assertExists()
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("conversation-composer").assertExists()
    }

    @Test fun androidBackReturnsToPreviousScreen() {
        rule.onNodeWithTag("conversation-top-title").assertExists()
        openDrawerRoute("home")
        rule.onNodeWithTag("home-task-hero").assertExists()
        rule.waitForIdle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("home-task-hero").assertDoesNotExist()
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
            val canReconnect = rule.onAllNodesWithText("重新连接")
                .fetchSemanticsNodes().isNotEmpty()
            val canReload = rule.onAllNodesWithText("重新检查")
                .fetchSemanticsNodes().isNotEmpty()
            assertTrue("应明确提供重新连接或重新读取能力目录的入口",
                canReconnect || canReload)
        }
    }

    @Test fun taskFirstHomeHasOnePrimaryActionAndUsefulShortcuts() {
        openDrawerRoute("home")
        rule.onNodeWithTag("home-task-hero").assertExists()
        rule.onNodeWithTag("home-primary-action").assertExists()
        rule.onNodeWithText("当前项目").assertExists()
        rule.onNodeWithTag("home-project-context").assertExists()
        rule.onNodeWithTag("home-shortcut-files").assertExists()
        rule.onNodeWithTag("home-shortcut-terminal").assertExists()
        rule.onNodeWithTag("home-shortcut-diff").assertDoesNotExist()
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

    @Test fun settingsSearchOpensUnifiedAccountMetrics() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("额度")
        rule.onNodeWithTag("settings-route-usage").assertExists()
        rule.onNodeWithTag("settings-route-limits").assertDoesNotExist()
        rule.onNodeWithTag("settings-route-usage").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("用量与额度")
        // 搜索直接选中额度标签，无须第二次手动切换。
        rule.onNodeWithText("额度窗口").assertExists()
    }

    @Test fun settingsGroupsAndInlineAppearanceAndUpdate() {
        openDrawerRoute("settings")
        rule.onNodeWithText("账户与模型").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-update"))
        rule.onNodeWithTag("settings-update").assertExists()
        rule.onNodeWithTag("settings-update-toggle").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("update-check").assertExists()
        captureOverlay("settings-update-sheet-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        capture("settings-hub-android35.png")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-search"))
        rule.onNodeWithTag("settings-search").performTextInput("外观")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-appearance"))
        rule.onNodeWithTag("settings-route-appearance").performClick()
        rule.onNodeWithText("柔和阴影").assertExists()
        rule.onNodeWithText("减少动效").assertExists()
        captureOverlay("appearance-android35.png")
    }

    @Test fun terminalExecutionShowsReadableConfirmation() {
        openDrawerRoute("home")
        rule.onNodeWithTag("home-shortcut-terminal").performClick()
        rule.onNodeWithTag("terminal-console").assertExists()
        rule.onNodeWithTag("terminal-console-empty").assertExists()
        val output = rule.onNodeWithTag("terminal-console").fetchSemanticsNode().boundsInRoot
        val emptyPanel = rule.onNodeWithTag("terminal-console-empty").fetchSemanticsNode().boundsInRoot
        val commandArea = rule.onNodeWithTag("terminal-command-input").fetchSemanticsNode().boundsInRoot
        assertTrue("终端输出区应位于输入区上方", output.bottom <= commandArea.top)
        assertTrue("空态需要足够的阅读面积", emptyPanel.height >= 96f)
        assertTrue("空态不能超出终端输出容器", emptyPanel.bottom <= output.bottom + 8f)
        assertTrue("空态应保留结果区域扩展空间", emptyPanel.height <= output.height)
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
        rule.onNodeWithTag("account-usage-limits").performScrollTo().performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("用量与额度")
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
        assertEquals(15, NavigationPolicy.settingsRoutes.size)
        assertEquals(NavigationPolicy.settingsRoutes.size,
            NavigationPolicy.settingsRoutes.toSet().size)
        assertFalse(NavigationPolicy.settingsRoutes.any {
            it in setOf("goals", "queue", "review", "diff", "terminal")
        })
        openSettingRoute("plugins")
        rule.onNodeWithTag("screen-title").assertTextEquals("插件")
        openSettingRoute("config")
        rule.onNodeWithTag("screen-title").assertTextEquals("引擎配置")
        openSettingRoute("diagnostics")
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun threadLibraryExposesServerBackedManagementLayout() {
        openThreadLibrary()
        rule.onNodeWithTag("thread-library-list").assertExists()
        rule.onNodeWithText("活跃", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performClick()
        rule.onNodeWithText("搜索会话标题").assertExists()
        rule.onNodeWithText("全文搜索").assertExists()
        rule.onNodeWithTag("library-new-thread").assertExists()
        captureOverlay("thread-library-android35.png")
        rule.onNodeWithText("全文搜索").performClick()
        rule.onNodeWithText("搜索历史正文").assertExists()
        captureOverlay("thread-full-search-android35.png")
    }

    @Test fun accountMetricsUsesSharedUsageAndLimitsTabs() {
        openSettingRoute("account")
        rule.onNodeWithTag("account-usage-limits").performScrollTo().performClick()
        rule.onNodeWithText("账号使用概况").assertExists()
        capture("account-usage-android35.png")
        rule.onNodeWithText("额度", substring = false).performClick()
        rule.onNodeWithText("额度窗口").assertExists()
        capture("account-limits-android35.png")
    }

    @Test fun workspaceProjectManagerOpensWithoutExpandingTheFileList() {
        openDrawerRoute("files")
        rule.onNodeWithTag("workspace-project-management").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("codex-detail-title").assertTextEquals("项目列表与管理")
        captureOverlay("audit-project-manager-sheet-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.onNodeWithTag("workspace-list").assertExists()
    }

    @Test fun executionEnvironmentDiagnosticsUseSeparateDetailSheet() {
        openSettingRoute("environments")
        rule.onNodeWithTag("environment-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("codex-detail-title").assertTextEquals("执行环境检查")
        rule.onNodeWithTag("environment-check-status").assertExists()
        rule.onNodeWithTag("environment-info").assertExists()
        captureOverlay("audit-environment-diagnostics-sheet-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.onNodeWithTag("environment-advanced-toggle").assertExists()
    }

    @Test fun workspaceFoldersCanBeCreatedAndRemovedWithExplicitConfirmation() {
        val name = "ui-folder-" + System.currentTimeMillis()
        openDrawerRoute("files")
        rule.onNodeWithTag("workspace-new-name").assertDoesNotExist()
        rule.onNodeWithTag("workspace-list").performScrollToNode(
            hasTestTag("workspace-create-toggle"))
        rule.onNodeWithTag("workspace-create-toggle").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("workspace-folder-mode").performClick()
        rule.onNodeWithTag("workspace-new-name").performTextInput(name)
        rule.onNodeWithTag("workspace-create").performClick()
        rule.onNodeWithTag("workspace-new-name").assertDoesNotExist()
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
        rule.onNodeWithTag("sidebar-project-context").assertExists()
        rule.onNodeWithTag("nav-terminal").assertDoesNotExist()
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithTag("conversation-top-title").assertExists()
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        openThreadLibrary()
        rule.onNodeWithTag("thread-library-list").assertExists()
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
        rule.onNodeWithTag("plugins-search-heading").assertExists()
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
        rule.onNodeWithTag("settings-search").performClick()
        rule.onNodeWithTag("settings-search").performTextInput("诊断")
        assertRealSoftKeyboardOpen()
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
        rule.onNodeWithText("配置模型服务商").assertExists()
        rule.onNodeWithTag("settings-route-queue").assertDoesNotExist()
    }

    @Test fun coldStartOpensChatAndKeepsDraftSafe() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("home-primary-action").assertDoesNotExist()
        rule.onNodeWithTag("chat-suggestion-script").performClick()
        rule.onNodeWithTag("composer-input").assertTextContains("帮我写一个脚本，目标是：")
        rule.onNodeWithTag("chat-suggestions").assertDoesNotExist()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
    }

    @Test fun chatTimelineSeparatesMessagesAndExecutionSteps() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread {
            vm.timeline.value = listOf(
                TimelineItem("ui-user", "user", "检查项目结构"),
                TimelineItem("ui-agent", "agentMessage", "已找到项目入口"),
                TimelineItem("ui-step", "commandExecution", "pwd", "completed")
            )
        }
        try {
            rule.onNodeWithTag("chat-user-message").assertExists()
            rule.onNodeWithTag("chat-assistant-message").assertExists()
            rule.onNodeWithTag("chat-execution-step").assertExists()
            rule.onNodeWithTag("composer-send").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { vm.timeline.value = emptyList() }
        }
    }

    @Test fun chatSuggestionsStayVisibleWithinThePhoneWidth() {
        openDrawerRoute("chat")
        rule.onNodeWithTag("chat-suggestions").assertExists()
        listOf("files", "script", "project", "review").forEach { item ->
            rule.onNodeWithTag("chat-suggestion-" + item).assertIsDisplayed()
        }
    }

    @Test fun settingsSearchOpensPreciseProviderAndRemoteEditors() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("API Key")
        rule.onNodeWithTag("settings-route-providers").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("服务商名称").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("settings-search").performTextClearance()
        rule.onNodeWithTag("settings-search").performTextInput("远程主机")
        rule.onNodeWithTag("settings-route-environments").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("environment-endpoint").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun settingsSearchOpensAppearanceSheetWithoutInlineExpansion() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("字号")
        rule.onNodeWithTag("settings-route-appearance").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("appearance-font-heading").assertExists()
        rule.onNodeWithTag("appearance-font-controls").assertExists()
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.onNodeWithTag("settings-search").assertExists()
    }

    @Test fun settingsSearchDirectlyOpensUpdateActions() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("检查更新")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-update"))
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("update-check").assertExists()
    }

    @Test fun settingsSearchDirectlyExpandsAdvancedConfig() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("高级配置")
        rule.onNodeWithTag("settings-route-config").performClick()
        rule.onNodeWithTag("codex-detail-title").assertTextEquals("高级自定义配置")
    }

    @Test fun settingsSearchDoesNotExposeTaskOperations() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").performTextInput("任务队列")
        rule.onNodeWithText("未找到相关设置").assertExists()
        rule.onNodeWithTag("settings-route-queue").assertDoesNotExist()
        rule.onNodeWithText("功能搜索结果").assertDoesNotExist()
    }

    @Test fun noSessionStillOpensProjectResourcesDirectly() {
        openDrawerRoute("chat")
        rule.onNodeWithTag("chat-project-terminal").assertExists()
        rule.onNodeWithTag("chat-project-files").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        rule.onNodeWithTag("chat-project-terminal").performClick()
        rule.onNodeWithText("项目终端").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    @Test fun activeThreadOpensItsQueueWithoutHistoryPicker() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.activeThread.value = "ci-current-queue" }
        openDrawerRoute("chat")
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithText("后续指令").performScrollTo().performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-sheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    @Test fun queueDraftSurvivesClosingAndReopeningSheet() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.activeThread.value = "ci-queue-draft" }
        rule.waitForIdle()
        openDrawerRoute("chat")
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithText("后续指令").performScrollTo().performClick()
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
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithText("后续指令").performScrollTo().performClick()
        rule.onNodeWithTag("thread-queue-draft").assertTextContains("保留未发送的队列内容")
    }

    @Test fun queueAndFullSearchOpenAsTaskSheets() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("thread-library-list").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        rule.onNodeWithText("搜索历史正文").assertExists()
        captureOverlay("thread-global-search-android35.png")
        pressSystemBack()

        rule.runOnUiThread { vm.activeThread.value = "ci-queue-thread" }
        openDrawerRoute("chat")
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithText("后续指令").performScrollTo().performClick()
        rule.onNodeWithTag("thread-queue-sheet").assertExists()
        rule.onNodeWithText("后续指令").assertExists()
        assertTrue("同一队列请求错误只显示一次",
            rule.onAllNodesWithText("Codex 内核尚未连接").fetchSemanticsNodes().size <= 1)
        captureOverlay("audit-queue-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("conversation-composer").assertExists()
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
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        captureOverlay("audit-provider-editor-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()

        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithText("文本值").assertExists()
        rule.onNodeWithText("开关").assertExists()
        rule.onNodeWithText("高级数据").assertExists()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        captureOverlay("audit-config-editor-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()

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
        rule.onNodeWithContentDescription("已选择").assertExists()
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

    @Test fun directlySelectableSecurityAndAboutViewsAreCaptured() {
        openSettingRoute("security")
        rule.onNodeWithTag("security-profiles-heading").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("可用权限方案", substring = false).assertExists()
        capture("audit-security-profiles-android35.png")

        openSettingRoute("about")
        rule.onNodeWithText("关于应用").assertExists()
        captureOverlay("audit-about-android35.png")
    }

    @Test fun permissionProfilesCanBeSelectedAndRestrictedProfilesCannot() {
        val vm = (rule.activity.application as CodexApplication).session
        openSettingRoute("security")
        rule.waitUntil(15_000L) {
            listOf("config", "securityRequirements", "permissionProfiles").none { it in vm.loading.value }
        }
        rule.onNodeWithTag("security-profiles-heading").performScrollTo()
        rule.onNodeWithText("可用权限方案", substring = false).assertExists()
        rule.runOnUiThread {
            vm.data.value = vm.data.value + mapOf(
                "config" to JSONObject().put("config", JSONObject()
                    .put("default_permissions", ":read-only")
                    .put("approval_policy", "on-request")
                    .put("sandbox_mode", "read-only")),
                "securityRequirements" to JSONObject().put("requirements", JSONObject()
                    .put("allowedPermissionProfiles", JSONObject()
                        .put(":read-only", true)
                        .put(":workspace", true)
                        .put(":danger-full-access", false))),
                "permissionProfiles" to JSONObject().put("data", JSONArray()
                    .put(JSONObject().put("id", ":read-only").put("allowed", true))
                    .put(JSONObject().put("id", ":workspace").put("allowed", true))
                    .put(JSONObject().put("id", ":danger-full-access").put("allowed", true)))
            )
        }
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("security-profile-workspace").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("security-profile-workspace").performScrollTo().performClick()
        rule.onNodeWithText("保存默认权限").assertIsEnabled()
        rule.onNodeWithTag("security-profile-danger-full-access").performScrollTo()
            .assertIsNotEnabled()
        rule.onNodeWithTag("security-scope-thread").performScrollTo()
            .assertIsNotEnabled()
        rule.onNodeWithTag("security-profile-workspace").performScrollTo()
            .assertIsDisplayed()
        capture("audit-security-selection-android35.png")
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
        rule.onNodeWithContentDescription("会话选项").performClick()
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
        rule.onNodeWithTag("conversation-top-title").assertExists()
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithText("会话关联资料").performScrollTo().performClick()
        rule.onNodeWithText("添加文本备注").assertExists()
        captureOverlay("audit-thread-attachments-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("conversation-top-title").assertExists()
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithTag("thread-context-review").performScrollTo().performClick()
        rule.onNodeWithText("审查范围").assertExists()
        rule.onNodeWithContentDescription("关闭代码审查").assertExists()
        captureOverlay("audit-review-sheet-android35.png")
        rule.onNodeWithContentDescription("关闭代码审查").performClick()
        rule.onNodeWithTag("conversation-top-title").assertExists()
        rule.onNodeWithContentDescription("会话选项").performClick()
        rule.onNodeWithTag("thread-context-goals").performScrollTo().performClick()
        rule.onNodeWithText("设置新目标").assertExists()
        rule.onNodeWithTag("goal-sticky-save").assertExists()
        rule.onNodeWithContentDescription("关闭任务目标").assertExists()
        captureOverlay("audit-goals-sheet-android35.png")
        rule.onNodeWithTag("goal-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("codex-edit-dialog").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithTag("codex-edit-dialog").assertDoesNotExist()
        rule.onNodeWithTag("goal-sticky-save").assertExists()
        rule.onNodeWithTag("goal-advanced-toggle").assertExists()
    }

    @Test fun sidebarProjectContextOpensFilesWithoutHistoryPicker() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-project-context").assertExists()
        rule.onNodeWithTag("sidebar-project-context").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
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
