package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
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

    /** Dispatch a physical display tap so WindowManager can deliver ACTION_OUTSIDE. */
    private fun tapOutsideFloatingMenu() {
        rule.waitForIdle()
        val center = rule.onNodeWithTag("conversation-top-bar").fetchSemanticsNode().boundsInWindow.center
        val screen = IntArray(2)
        val window = IntArray(2)
        rule.runOnUiThread {
            rule.activity.window.decorView.getLocationOnScreen(screen)
            rule.activity.window.decorView.getLocationInWindow(window)
        }
        val x = center.x + screen[0] - window[0]
        val y = center.y + screen[1] - window[1]
        val popup = rule.onNodeWithTag("codex-floating-menu").fetchSemanticsNode()
        rule.runOnIdle {
            val coords = popup.layoutInfo.coordinates
            val origin = coords.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
            val bounds = androidx.compose.ui.geometry.Rect(origin.x, origin.y,
                origin.x + coords.size.width, origin.y + coords.size.height)
            check(origin.x.isFinite() && origin.y.isFinite()) { "浮动菜单尚未获得真实屏幕坐标" }
            check(!bounds.contains(androidx.compose.ui.geometry.Offset(x, y))) {
                "顶部关闭区域被菜单覆盖：tap=($x,$y), popup=$bounds"
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                action, x, y, 0)
            try {
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(event)
            } finally { event.recycle() }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("codex-floating-menu").fetchSemanticsNodes().isEmpty()
        }
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

    private fun openComposerShortcut(command: String) {
        val input = rule.onNodeWithTag("composer-input")
        input.performTextReplacement("/$command")
        rule.onNodeWithTag("composer-shortcut-$command").assertHasClickAction().performClick()
        rule.onNodeWithTag("composer-shortcuts").assertDoesNotExist()
    }

    private fun assertNoWorkspaceBar() {
        rule.onNodeWithTag("chat-workspace-context").assertDoesNotExist()
        rule.onNodeWithTag("chat-project-files").assertDoesNotExist()
        rule.onNodeWithTag("chat-project-terminal").assertDoesNotExist()
        rule.onNodeWithContentDescription("会话选项").assertDoesNotExist()
    }

    private fun openThreadLibrary() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    private fun openSettingRoute(id: String) {
        if (rule.onAllNodesWithContentDescription("返回上一页").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription("返回上一页").performClick()
            rule.waitForIdle()
        }
        if (rule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isEmpty()) openDrawerRoute("settings")
        rule.waitUntil(5_000L) { rule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("screen-title").assertTextEquals("设置")
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
        if (id == "account" || id == "diagnostics") {
            val target = if (id == "account") "settings-account-overview" else "settings-engine-overview"
            rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(target))
            rule.onNodeWithTag(target).performClick()
        } else {
            rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-" + id))
            rule.onNodeWithTag("settings-route-" + id).performClick()
        }
    }

    @Test fun drawerOpensAndNavigatesToConversation() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("home-task-hero").assertDoesNotExist()
        rule.onNodeWithText("今天想做什么？").assertIsDisplayed()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            rule.activity.requestedOrientation)
        rule.onNodeWithTag("conversation-top-title").assertDoesNotExist()
        rule.onNodeWithText("新任务", substring = false).assertDoesNotExist()
        assertNoWorkspaceBar()
        capture("chat-android35.png")
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").assertDoesNotExist()
        rule.onNodeWithTag("nav-home").assertDoesNotExist()
        rule.onNodeWithTag("nav-settings").assertExists()
        rule.onNodeWithTag("nav-plugins").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertIsDisplayed()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        capture("sidebar-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        rule.onNodeWithText("今天想做什么？").assertExists()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
    }

    @Test fun sidebarQuotaOpensLimitsWithoutFooterLabels() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        val quota = rule.onNodeWithTag("nav-limits").assertIsDisplayed().assertHasClickAction()
        val settings = rule.onNodeWithTag("nav-settings").assertIsDisplayed()
        val account = rule.onNodeWithTag("nav-account").assertIsDisplayed()
        val bounds = quota.fetchSemanticsNode().boundsInRoot
        assertEquals(settings.fetchSemanticsNode().boundsInRoot.width, bounds.width, 1f)
        assertEquals(settings.fetchSemanticsNode().boundsInRoot.height, bounds.height, 1f)
        assertEquals(account.fetchSemanticsNode().boundsInRoot.width, bounds.width, 1f)
        rule.onNodeWithText("账号与设置", substring = false).assertDoesNotExist()
        rule.onNodeWithText("账号", substring = false).assertDoesNotExist()
        rule.onNodeWithText("设置", substring = false).assertDoesNotExist()
        quota.performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("使用限额")
        rule.onNodeWithText("额度窗口", substring = false).assertIsDisplayed()
        rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
        pressSystemBack()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
    }

    @Test fun workTopBarRespectsSystemStatusBarAndCutout() {
        fun assertSafeTopBar() {
            rule.onNodeWithTag("drawer-toggle").assertIsDisplayed()
            val button = rule.onNodeWithTag("drawer-toggle").fetchSemanticsNode()
            val newTask = rule.onNodeWithTag("top-new-chat").fetchSemanticsNode()
            var safeTop = 0
            rule.runOnUiThread {
                val insets = requireNotNull(rule.activity.window.decorView.rootWindowInsets)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    safeTop = insets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.displayCutout()).top
                }
            }
            assertTrue("顶栏按钮进入状态栏", button.boundsInWindow.top >= safeTop)
            assertTrue("新建任务按钮进入状态栏", newTask.boundsInWindow.top >= safeTop)
        }
        assertSafeTopBar()
        rule.onNodeWithTag("top-new-chat").performClick()
        assertSafeTopBar()
    }

    @Test fun homepageRightSwipeOpensDrawerWithoutStealingPageGestures() {
        val vm = (rule.activity.application as CodexApplication).session
        val oldMotion = UiPreferences.reducedMotion.value
        try {
            rule.runOnUiThread {
                UiPreferences.reducedMotion.value = false
                vm.editComposerText(null, "横滑及转场保留的草稿")
            }
            rule.onNodeWithTag("composer-input").assertTextContains("横滑及转场保留的草稿")
            val work = rule.onNodeWithTag("work-shell")
            // 中央与右半屏也可起滑，不再限制左侧 96dp。
            work.performTouchInput {
                swipe(Offset(width * .48f, height * .35f), Offset(width * .86f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertIsDisplayed()
            pressSystemBack()
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            work.performTouchInput {
                swipe(Offset(width * .62f, height * .35f), Offset(width * .94f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertIsDisplayed()
            pressSystemBack()
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            // 左滑、短距离拖动与竖滑均不打开侧栏。
            work.performTouchInput {
                swipe(Offset(width * .70f, height * .35f), Offset(width * .25f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            work.performTouchInput {
                swipe(Offset(width * .48f, height * .35f), Offset(width * .52f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            work.performTouchInput {
                swipe(Offset(width * .10f, height * .25f), Offset(width * .10f, height * .55f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            work.performTouchInput {
                swipe(Offset(width * .10f, height * .35f), Offset(width * .65f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertIsDisplayed().performTouchInput { swipeLeft() }
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            rule.onNodeWithTag("composer-input").assertTextContains("横滑及转场保留的草稿")

            // 真正展开软键盘后，从顶栏中央右滑仍可打开，并保留输入草稿。
            rule.onNodeWithTag("composer-input").performClick()
            assertRealSoftKeyboardOpen()
            rule.onNodeWithTag("conversation-top-bar").performTouchInput {
                swipe(Offset(width * .35f, height * .5f), Offset(width * .80f, height * .5f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertIsDisplayed()
            rule.waitUntil(10_000L) {
                val insets = rule.activity.window.decorView.rootWindowInsets
                insets != null && !insets.isVisible(android.view.WindowInsets.Type.ime())
            }
            pressSystemBack()
            rule.onNodeWithTag("composer-input").assertTextContains("横滑及转场保留的草稿")

            rule.runOnUiThread { UiPreferences.reducedMotion.value = true }
            work.performTouchInput {
                swipe(Offset(width * .48f, height * .35f), Offset(width * .86f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertIsDisplayed()
            rule.onNodeWithTag("nav-settings").performClick()
            rule.onNodeWithTag("screen-title").assertTextEquals("设置")
            // The regression occurred on an ordinary clickable settings row, not
            // the drawer itself. Both swipe directions must cancel the row tap.
            val engineRow = rule.onNodeWithTag("settings-engine-overview").assertIsDisplayed()
            engineRow.performTouchInput { swipeRight() }
            rule.onNodeWithTag("screen-title").assertTextEquals("设置")
            engineRow.performTouchInput { swipeLeft() }
            rule.onNodeWithTag("screen-title").assertTextEquals("设置")
            engineRow.performTouchInput { click() }
            rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
            pressSystemBack()
            rule.onNodeWithTag("screen-title").assertTextEquals("设置")
            work.performTouchInput {
                swipe(Offset(width * .10f, height * .35f), Offset(width * .65f, height * .35f))
            }
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            rule.onAllNodesWithTag("screen-title").assertCountEquals(1)
            rule.onNodeWithTag("screen-title").assertTextEquals("设置")
            rule.onNodeWithTag("conversation-composer").assertDoesNotExist()
            rule.onNodeWithContentDescription("打开侧边导航").performTouchInput { click() }
            pressSystemBack()
            rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
            rule.onNodeWithTag("screen-title").assertTextEquals("设置")
            pressSystemBack()
            rule.onNodeWithTag("composer-input").assertTextContains("横滑及转场保留的草稿")
            rule.onNodeWithTag("settings-search").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { UiPreferences.reducedMotion.value = oldMotion }
        }
    }

    @Test fun backgroundApprovalOpensFromChatWithoutMislabelingTheCurrentTask() {
        val vm = (rule.activity.application as CodexApplication).session
        val prompt = ServerPrompt(91, "item/commandExecution/requestApproval",
            JSONObject().put("threadId", "other-thread").put("command", "git status"))
        try {
            rule.runOnUiThread {
                vm.activeThread.value = "visible-thread"
                vm.bridge.prompts.value = listOf(prompt)
            }
            rule.onNodeWithText("立即处理").assertDoesNotExist()
            rule.onNodeWithTag("chat-other-approvals").assertIsDisplayed().performClick()
            rule.onNodeWithText("命令执行确认").assertExists()
        } finally {
            rule.runOnUiThread {
                vm.bridge.prompts.value = emptyList()
                vm.activeThread.value = null
            }
        }
    }

    @Test fun offscreenRunningTaskBlocksLocalDataTransferButtons() {
        val vm = (rule.activity.application as CodexApplication).session
        openDrawerRoute("settings")
        // The backup card lives inside a lazily composed settings group.
        // Search and scroll to the actual section before asserting child actions.
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

    @Test fun failedVoiceStopRemainsRecoverableOnSettingsAndBlocksDataTransfer() {
        val vm = (rule.activity.application as CodexApplication).session
        val owner = Any()
        var retries = 0
        try {
            rule.runOnUiThread {
                check(vm.realtimeVoiceLease.acquire(owner))
                vm.realtimeVoiceLease.markFailed(owner, "未收到关闭回执") { retries++ }
            }
            rule.onNodeWithText("重试结束").assertIsDisplayed()
            openDrawerRoute("settings")
            rule.onNodeWithText("重试结束").performClick()
            rule.runOnIdle { assertEquals(1, retries) }
                rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-backup"))
            rule.onNodeWithTag("backup-export").assertIsNotEnabled()
            rule.onNodeWithTag("backup-import").assertIsNotEnabled()
            rule.runOnUiThread { vm.realtimeVoiceLease.release(owner) }
            rule.waitForIdle()
            rule.onNodeWithText("重试结束").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { vm.realtimeVoiceLease.release(owner) }
            rule.waitForIdle()
        }
    }

    @Test fun globalArchiveOverlayProtectsSettingsAndRestoresThePage() {
        openDrawerRoute("settings")
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
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
    }

    @Test fun sidebarKeepsChatAndWorkbenchAsOneNavigationPath() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("conversation-top-title").assertDoesNotExist()
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").assertDoesNotExist()
        rule.onNodeWithTag("nav-home").assertDoesNotExist()
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
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("composer-input").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送给 Codex").assertExists()
        // 空闲时隐藏停止操作，避免把禁用按钮长期占在输入区。
        rule.onNodeWithTag("composer-stop").assertDoesNotExist()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
    }

    @Test fun chatKeyboardKeepsComposerActionsAccessible() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("composer-input").performClick()
        val multiline = (1..6).joinToString("\n") { "验证键盘展开后的操作入口 $it" }
        rule.onNodeWithTag("composer-input").performTextInput(multiline)
        rule.onNodeWithTag("composer-input").assertTextContains(multiline)
        rule.waitForIdle()
        // 必须由系统确认软键盘真实展开；仅能输入文字或按钮可见均不足以验收 IME。
        assertRealSoftKeyboardOpen()
        // Compose-root captures exclude the Android IME window. Capture the full
        // device display so the screenshot actually proves keyboard occlusion.
        captureVerifiedDisplayScreenshot("chat-keyboard-open-android35.png")
        assertRealSoftKeyboardOpen()
        rule.onNodeWithTag("composer-expand").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
        rule.onNodeWithTag("composer-task-options").assertIsDisplayed()
        rule.onNodeWithTag("conversation-model-control").assertIsDisplayed()
        rule.onNodeWithTag("conversation-mode-control").assertIsDisplayed()
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("composer-add-photos").assertIsDisplayed()
        assertRealSoftKeyboardOpen()
        tapOutsideFloatingMenu()
        rule.onNodeWithTag("codex-floating-menu").assertDoesNotExist()
        rule.onNodeWithTag("composer-input").assertTextContains(multiline)
    }

    @Test fun narrowConversationToolbarKeepsPrimaryActionsVisible() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("conversation-model-control").assertIsDisplayed()
        // 模式由真实内核恢复，可能是「执行」或「先计划」；校验实际可交互的按钮。
        rule.onNodeWithTag("conversation-mode-control").assertIsDisplayed()
        rule.onNode(hasTestTag("conversation-mode-control") and hasAnyAncestor(hasTestTag("composer-context-footer"))).assertExists()
        rule.onNode(hasTestTag("conversation-model-control") and hasAnyAncestor(hasTestTag("composer-primary-actions"))).assertExists()
        rule.onNode(hasTestTag("composer-task-options") and hasAnyAncestor(hasTestTag("conversation-composer"))).assertDoesNotExist()
        rule.onNodeWithContentDescription("查看内核运行诊断").assertDoesNotExist()
        rule.onNodeWithContentDescription("添加内容").assertIsDisplayed()
        rule.onNodeWithTag("composer-task-options").assertIsDisplayed()
        capture("chat-toolbar-android35.png")
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithText("添加内容").assertExists()
        rule.onNodeWithTag("composer-add-photos").assertExists()
        rule.onNodeWithTag("composer-add-files").assertExists()
        rule.onNodeWithTag("composer-add-reference").assertExists()
        rule.onNodeWithTag("composer-add-skill").assertExists()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        captureOverlay("attachment-sheet-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("codex-floating-menu").assertDoesNotExist()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        captureOverlay("terminal-sheet-android35.png")
    }

    @Test fun statusButtonHasStableMeaningAndSeparatesActions() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("composer-task-options").performClick()
        rule.onNodeWithTag("composer-tool-status").performScrollTo().performClick()
        rule.onNodeWithText("运行状态").assertExists()
        rule.onNodeWithText("查看诊断和日志").assertExists()
        captureOverlay("engine-status-android35.png")
    }

    @Test fun modelChoiceUsesOneSharedBottomSheet() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("conversation-model-control").performClick()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("composer-effort").assertExists()
        rule.onNodeWithTag("codex-floating-title").assertTextEquals("选择运行模型")
        captureOverlay("model-sheet-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("codex-floating-menu").assertDoesNotExist()
    }

    @Test fun composerReferenceAndExpandedEditorPreserveDraft() {
        val vm = (rule.activity.application as CodexApplication).session
        val fixture = java.io.File(vm.activeWorkspace.value, "composer-reference-ci.txt")
        fixture.writeText("reference fixture")
        try {
            rule.onNodeWithTag("composer-input").performTextInput("检查 @composer-reference-ci.txt")
            rule.onNodeWithTag("composer-reference-suggestion").performClick()
            val resultTag = "composer-reference-result-" + fixture.canonicalPath
            rule.waitUntil(10000) {
                rule.onAllNodesWithTag(resultTag).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag(resultTag).performClick()
            rule.onNodeWithTag("composer-references").assertExists()
            rule.onNodeWithTag("composer-input").assertTextContains("检查")
            rule.onNodeWithTag("composer-task-options").performClick()
            rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
            // Click outside the anchored popup without altering the draft.
            tapOutsideFloatingMenu()
            rule.onNodeWithTag("codex-floating-menu").assertDoesNotExist()
            rule.onNodeWithTag("composer-input").assertTextContains("检查")
            rule.onNodeWithTag("composer-input").performTextInput("\n" + "继续核对项目内容".repeat(20))
            rule.onNodeWithTag("composer-expand").performClick()
            rule.onNodeWithTag("composer-expanded-input").assertExists()
            rule.onNodeWithText("完成").performClick()
            rule.onNodeWithTag("composer-input").assertTextContains("继续核对项目内容", substring = true)
            rule.onNodeWithContentDescription("移除引用 composer-reference-ci.txt").performClick()
            rule.onNodeWithTag("composer-references").assertDoesNotExist()
        } finally {
            fixture.delete()
        }
    }

    @Test fun diffReaderShowsFileFirstSummaryAndReviewAction() {
        val vm = (rule.activity.application as CodexApplication).session
        val sampleDiff = "diff --git a/src/main.kt b/src/main.kt\n" +
            "@@ -1 +1 @@\n-before\n+after"
        rule.runOnUiThread {
            vm.activeThread.value = "ci-diff-thread"
            vm.latestDiff.value = sampleDiff
        }
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("chat-changes-summary").assertExists()
        rule.onNodeWithText("查看", substring = false).performClick()
        rule.onNodeWithText("本次改动").assertExists()
        rule.onNodeWithText("代码审查").assertExists()
        rule.onNodeWithText("src/main.kt").assertExists()
        capture("diff-reader-android35.png")
    }

    @Test fun projectTerminalAndFilesReturnToConversation() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        rule.onNodeWithTag("terminal-console").assertExists()
        pressSystemBack()
        rule.onNodeWithTag("conversation-composer").assertExists()
        openComposerShortcut("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        // Project files offer both a global session drawer and a contextual return.
        rule.onNodeWithContentDescription("打开侧边导航").assertExists()
        rule.onNodeWithTag("files-return-to-conversation").assertExists()
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("conversation-composer").assertExists()
    }

    @Test fun androidBackReturnsToPreviousScreen() {
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        rule.waitForIdle()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("home-task-hero").assertDoesNotExist()
    }

    @Test fun modeSheetRespectsRealEngineCapability() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithText("执行", substring = false).performClick()
        rule.onNodeWithText("选择协作方式").assertExists()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
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
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("conversation-top-title").assertDoesNotExist()
        assertNoWorkspaceBar()
        rule.onNodeWithText("本机工作区", substring = false).assertDoesNotExist()
        openComposerShortcut("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        pressSystemBack()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        rule.onNodeWithTag("terminal-console").assertExists()
        pressSystemBack()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
    }

    @Test fun settingsOverviewAndLocalBackupCanBeFoundByIntent() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-overview").assertExists()
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-backup"))
        rule.onNodeWithTag("settings-backup").assertExists()
    }

    @Test fun settingsSearchOpensUnifiedAccountMetrics() {
        openSettingRoute("usage")
        rule.onNodeWithTag("screen-title").assertTextEquals("用量与额度")
        rule.onNodeWithText("额度窗口").assertExists()
    }

    @Test fun settingsGroupsAndInlineAppearanceAndUpdate() {
        openDrawerRoute("settings")
        rule.onNodeWithText("账户与模型").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-update"))
        rule.onNodeWithTag("settings-update").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("update-check"))
        rule.onNodeWithTag("update-check").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        captureOverlay("settings-update-sheet-android35.png")
        capture("settings-hub-android35.png")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-appearance"))
        rule.onNodeWithTag("settings-route-appearance").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("外观与显示")
        rule.onNodeWithText("柔和阴影").assertExists()
        rule.onNodeWithText("减少动效").assertExists()
        captureOverlay("appearance-android35.png")
    }

    @Test fun terminalExecutionShowsReadableConfirmation() {
        openComposerShortcut("terminal")
        rule.onNodeWithTag("terminal-console").assertExists()
        rule.onNodeWithTag("terminal-console-empty").assertExists()
        val output = rule.onNodeWithTag("terminal-console").fetchSemanticsNode().boundsInRoot
        val emptyPanel = rule.onNodeWithTag("terminal-console-empty").fetchSemanticsNode().boundsInRoot
        val commandArea = rule.onNodeWithTag("terminal-command-input").fetchSemanticsNode().boundsInRoot
        assertTrue("终端输出区应位于输入区上方", output.bottom <= commandArea.top)
        assertTrue("空态需要足够的阅读面积", emptyPanel.height >= 96f)
        assertTrue("空态不能超出终端输出容器", emptyPanel.bottom <= output.bottom + 8f)
        assertTrue("空态应保留结果区域扩展空间", emptyPanel.height <= output.height)
        captureOverlay("terminal-android35.png")
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
        assertEquals(31, pages.size)
        assertEquals(30, UiFeatureCatalog.legacyRoutes.size)
        assertEquals(pages.filter { it.id != "settings" }.map { it.id }.toSet(),
            UiFeatureCatalog.legacyRoutes.toSet())
        assertEquals(5, NavigationPolicy.featureGroups.size)
        assertEquals(18, NavigationPolicy.settingsRoutes.size)
        assertEquals(NavigationPolicy.settingsRoutes.size,
            NavigationPolicy.settingsRoutes.toSet().size)
        assertFalse(NavigationPolicy.settingsRoutes.any {
            it in setOf("goals", "queue", "review", "diff", "terminal")
        })
        openSettingRoute("plugins")
        rule.onNodeWithTag("screen-title").assertTextEquals("插件")
        openSettingRoute("features")
        rule.onNodeWithTag("screen-title").assertTextEquals("功能开关")
        rule.onNodeWithTag("feature-switch-list").assertExists()
        rule.onNodeWithTag("feature-switch-search").assertDoesNotExist()
        openSettingRoute("config")
        rule.onNodeWithTag("screen-title").assertTextEquals("开发者设置")
        openSettingRoute("diagnostics")
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun threadLibraryExposesServerBackedManagementLayout() {
        openThreadLibrary()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithText("会话", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertExists()
        rule.onNodeWithText("全文搜索").assertExists()
        rule.onNodeWithTag("sidebar-new-chat").assertExists()
        rule.onNodeWithTag("thread-library-list").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-management").performClick()
        rule.onNodeWithTag("codex-detail-title").assertTextEquals("会话管理")
        rule.onNodeWithTag("sidebar-manage-sections").assertHasClickAction()
        rule.onNodeWithText("批量管理").assertExists()
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithText("下一页").assertDoesNotExist()
        captureOverlay("thread-library-android35.png")
        rule.onNodeWithText("全文搜索").performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertExists()
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
        rule.onNodeWithTag("workspace-new-name").performClick()
        rule.onNodeWithTag("action-reply-input").performTextReplacement(name)
        rule.onNodeWithTag("action-reply-send").performClick()
        rule.onNodeWithTag("workspace-create").performClick()
        rule.onNodeWithTag("workspace-new-name").assertDoesNotExist()
        rule.waitUntil(10000) {
            rule.onAllNodesWithTag("workspace-directory-loading").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("workspace-list").performScrollToNode(
            hasTestTag("workspace-menu-" + name))
        rule.onNodeWithTag("workspace-menu-" + name).assertExists()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(WorkspaceStorage.root(context), name)
        org.junit.Assert.assertTrue("目录须真实存在于手机 Download/Codex", folder.isDirectory)
        val external = java.io.File(folder.parentFile, "external-" + name).apply { mkdir() }
        try {
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-menu-" + external.name)); true }.getOrDefault(false)
            }
            rule.onNodeWithTag("workspace-menu-" + external.name).assertExists()
        } finally { external.delete() }
        rule.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-menu-" + name))
        capture("workspace-files-android35.png")
        // 创建目录后由业务页面自动收起键盘，并在此验收该交互。
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-menu-" + name))
        rule.onNodeWithTag("workspace-menu-" + name).performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("删除", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        val duringMenu = java.io.File(folder.parentFile, "00-during-menu-" + name)
        org.junit.Assert.assertTrue(duringMenu.mkdir())
        try {
            // Wait for the external directory to publish while the menu is open.
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("workspace-menu-" + duringMenu.name).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("删除", substring = false).assertExists().performClick()
        } finally { duringMenu.delete() }
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
        org.junit.Assert.assertFalse("删除须同步至真实手机目录", folder.exists())
    }

    @Test fun desktopStyleSidebarIsTheOnlyPrimaryNavigation() {
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-home").assertDoesNotExist()
        rule.onNodeWithTag("nav-files").assertExists()
        rule.onNodeWithTag("nav-threads").assertDoesNotExist()
        rule.onNodeWithTag("nav-approvals").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-new-chat").assertExists()
        rule.onNodeWithTag("sidebar-project-context").assertExists()
        rule.onNodeWithTag("nav-terminal").assertDoesNotExist()
        pressSystemBack()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        rule.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        openDrawerRoute("files")
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        openThreadLibrary()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        pressSystemBack()
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-engine-overview"))
        rule.onNodeWithTag("settings-engine-overview").performClick()
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
        rule.onNodeWithTag("plugins-clipboard-query").assertExists()
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
        openSettingRoute("diagnostics")
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("settings-list").assertExists()
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
    }


    @Test fun settingsSearchFindsActualOptionsByUserWording() {
        openSettingRoute("providers")
        rule.onNodeWithTag("screen-title").assertTextEquals("模型服务商")
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
    }

    @Test fun coldStartOpensChatAndKeepsDraftSafe() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("home-primary-action").assertDoesNotExist()
        rule.onNodeWithTag("composer-input").performTextInput("帮我写一个脚本，目标是：")
        rule.onNodeWithTag("composer-input").assertTextContains("帮我写一个脚本，目标是：")
        rule.onNodeWithTag("chat-suggestions").assertDoesNotExist()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
    }

    @Test fun chatTimelineSeparatesMessagesAndExecutionSteps() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread {
            vm.timeline.value = listOf(
                TimelineItem("ui-user", "user", "检查项目结构"),
                TimelineItem("ui-agent", "agentMessage", "已找到项目入口", "completed", turnId = "ui-turn"),
                TimelineItem("ui-step", "commandExecution", "pwd", "completed")
            )
        }
        try {
            rule.onNodeWithTag("chat-user-message").assertExists()
            rule.onNodeWithTag("chat-assistant-message").assertExists()
            rule.onNodeWithTag("message-copy-ui-agent").assertExists()
            rule.onNodeWithTag("message-retry-ui-agent").assertExists()
            rule.onNodeWithTag("message-share-ui-agent").assertExists()
            rule.onNodeWithTag("chat-activity-group").assertExists()
            rule.onNodeWithTag("chat-activity-group").performClick()
            rule.onNodeWithText("pwd").assertExists()
            rule.onNodeWithTag("composer-send").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { vm.timeline.value = emptyList() }
        }
    }

    @Test fun emptyChatHasNoSuggestionCardsAndKeepsComposerAccessible() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithText("今天想做什么？").assertIsDisplayed()
        rule.onNodeWithTag("chat-suggestions").assertDoesNotExist()
        listOf("files", "script", "project", "review").forEach { item ->
            rule.onNodeWithTag("chat-suggestion-" + item).assertDoesNotExist()
        }
        listOf("浏览文件", "编写脚本", "新建项目", "代码审查").forEach { title ->
            rule.onNodeWithText(title, substring = false).assertDoesNotExist()
        }
        assertNoWorkspaceBar()
        rule.onNodeWithTag("composer-input").assertIsDisplayed()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
    }

    @Test fun settingsSearchOpensPreciseProviderAndRemoteEditors() {
        openSettingRoute("providers")
        rule.onNodeWithTag("screen-title").assertTextEquals("模型服务商")
        openSettingRoute("environments")
        rule.onNodeWithTag("screen-title").assertTextEquals("运行管理")
    }

    @Test fun settingsSearchOpensAppearanceSheetWithoutInlineExpansion() {
        openSettingRoute("appearance")
        rule.onNodeWithTag("screen-title").assertTextEquals("外观与显示")
        rule.onNodeWithTag("appearance-font-heading").assertExists()
        rule.onNodeWithTag("appearance-font-controls").assertExists()
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
    }

    @Test fun settingsSearchDirectlyOpensUpdateActions() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("update-check"))
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.onNodeWithTag("update-check").assertIsDisplayed()
    }

    @Test fun settingsSearchSeparatesDiagnosticsOverviewFromLogAction() {
        openSettingRoute("diagnostics")
        rule.onNodeWithText("查看启动日志").assertHasClickAction()
    }

    @Test fun settingsPreferencesAreDistributedToTheirOwners() {
        val routes = listOf("models", "personalization", "context", "tools", "environments", "appearance", "config")
        for (route in routes) {
            openSettingRoute(route)
            rule.onNodeWithTag("screen-title").assertTextEquals(pages.first { it.id == route }.title)
            EngineSettings.forOwner(route).forEach {
                rule.onNodeWithTag("engine-setting-" + it.key).assertExists()
            }
            EngineSettings.all.filter { it.owner != route }.forEach {
                rule.onNodeWithTag("engine-setting-" + it.key).assertDoesNotExist()
            }
            if (route == "models") {
                rule.onNodeWithTag("engine-setting-model").performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("engine-slider-value-model").assertExists()
                rule.onNodeWithTag("engine-setting-model_reasoning_effort").performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("engine-setting-plan_mode_reasoning_effort").performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("engine-slider-model_verbosity").performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
                rule.onNodeWithTag("engine-setting-model").performScrollTo()
            }
            if (route in listOf("models", "personalization", "context", "tools"))
                captureVerifiedDisplayScreenshot("audit-" + route + "-android35.png")
        }
    }

    @Test fun settingsSearchDirectlyExpandsAdvancedConfig() {
        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("config-agent-action").assertExists()
    }

    @Test fun advancedConfigPreparesAgentDraftWithoutSubmitting() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.editComposerText(vm.activeThread.value, "原有草稿") }
        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("config-agent-action").performClick()
        rule.onNodeWithText("交给 Codex 处理").assertExists()
        rule.onNodeWithTag("confirm-dialog-accept").performClick()
        rule.onNodeWithTag("composer-input").assertTextContains("请帮我调整本机 Codex", substring = true)
        rule.runOnIdle {
            org.junit.Assert.assertFalse(vm.running.value)
            org.junit.Assert.assertEquals("原有草稿", vm.composerDrafts.value[ThreadComposerDrafts.key(null, vm.activeWorkspace.value)]?.text)
        }
        rule.onNodeWithTag("agent-draft-standard").performClick()
        rule.onNodeWithTag("composer-input").assertTextContains("原有草稿", substring = true)
        rule.onNodeWithTag("agent-draft-reopen").performClick()
        rule.onNodeWithTag("composer-input").assertTextContains("请帮我调整本机 Codex", substring = true)
        rule.runOnIdle { org.junit.Assert.assertFalse(vm.running.value) }
    }

    @Test fun settingsSearchDoesNotExposeTaskOperations() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
        rule.onNodeWithTag("settings-route-queue").assertDoesNotExist()
    }

    @Test fun noSessionStillOpensProjectResourcesDirectly() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        assertNoWorkspaceBar()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        rule.onNodeWithTag("terminal-console").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    @Test fun activeThreadOpensItsQueueWithoutHistoryPicker() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.activeThread.value = "ci-current-queue" }
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        assertNoWorkspaceBar()
        openComposerShortcut("queue")
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-draft").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    @Test fun queueDraftSurvivesClosingAndReopeningSheet() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.runOnUiThread { vm.activeThread.value = "ci-queue-draft" }
        rule.waitForIdle()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("queue")
        rule.onNodeWithTag("thread-queue-draft").performTextInput("保留未发送的队列内容")
        rule.runOnUiThread {
            val input = rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            rule.activity.currentFocus?.clearFocus()
        }
        rule.waitForIdle()
        // A visible close button must work regardless of IME focus or Back delivery.
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-draft").fetchSemanticsNodes().isEmpty()
        }
        openComposerShortcut("queue")
        rule.onNodeWithTag("thread-queue-draft").assertTextContains("保留未发送的队列内容")
    }

    @Test fun queueAndFullSearchOpenAsTaskSheets() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        rule.onNodeWithText("全文搜索").performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertExists()
        captureOverlay("thread-global-search-android35.png")
        pressSystemBack()

        rule.runOnUiThread { vm.activeThread.value = "ci-queue-thread" }
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("queue")
        rule.onNodeWithTag("screen-title").assertTextEquals("任务队列")
        rule.onNodeWithTag("thread-queue-draft").assertExists()
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
            "environments", "config", "diagnostics"
        )
        routeScreens.forEach { route ->
            openSettingRoute(route)
            rule.onNodeWithTag("screen-title")
                .assertTextEquals(pages.first { it.id == route }.title)
            rule.waitForIdle()
            if (route == "apps") {
                // 同页不同接口可能同时断连；用户只看到一条相同原因。
                assertTrue(rule.onAllNodesWithText("Codex 内核尚未连接")
                    .fetchSemanticsNodes().size <= 1)
            }
            if (route == "diagnostics") {
                // 搜索完整页面名称应停留在概览，不能误开日志对话框。
                rule.onAllNodes(isRoot()).assertCountEquals(1)
                rule.onNodeWithText("查看启动日志").assertHasClickAction()
                captureVerifiedDisplayScreenshot("audit-diagnostics-android35.png")
            } else capture("audit-" + route + "-android35.png")
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
        rule.onNodeWithTag("screen-title").assertTextEquals("待审批操作")
        captureOverlay("audit-approvals-android35.png")
        pressSystemBack()
        rule.runOnUiThread { vm.bridge.prompts.value = emptyList() }

        openSettingRoute("providers")
        rule.onNodeWithText("添加自定义服务商").performScrollTo().performClick()
        rule.onNodeWithTag("provider-endpoint").assertExists()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        captureOverlay("audit-provider-editor-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()

        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("config-agent-action").assertExists()
        rule.onNodeWithText("设置项名称").assertDoesNotExist()
        rule.onNodeWithText("高级数据").assertDoesNotExist()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        captureOverlay("audit-config-editor-android35.png")
        rule.onNodeWithTag("codex-detail-close").performClick()

        openSettingRoute("environments")
        rule.onNodeWithTag("environment-remote-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-remote-name").assertDoesNotExist()
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
        rule.onNodeWithTag("environment-remote-name").assertDoesNotExist()
        rule.onNodeWithTag("environment-endpoint").assertExists()
        rule.onNodeWithTag("environment-connect").assertIsNotEnabled()
        rule.onNodeWithTag("environment-endpoint").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000L) { rule.onAllNodesWithTag("action-reply-input").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("action-reply-input").performScrollTo().performTextInput("wss://example.com/exec")
        rule.onNodeWithTag("action-reply-send").performScrollTo().performClick()
        rule.waitUntil(5_000L) { rule.onAllNodesWithTag("environment-remote-name").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("environment-remote-name").assertTextContains("example.com", substring = true)
        rule.onNodeWithTag("environment-remote-name").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("action-reply-input").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("action-reply-input").performTextReplacement("工作电脑")
        rule.onNodeWithTag("action-reply-send").performClick()
        rule.onNodeWithTag("environment-connect").assertIsEnabled()

        rule.onNodeWithTag("environment-local-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-endpoint").assertDoesNotExist()
        rule.onNodeWithTag("environment-local-option").assertExists()
        rule.onNodeWithTag("environment-remote-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-endpoint").assertTextContains("wss://example.com/exec")
        rule.onNodeWithTag("environment-remote-name").assertTextContains("工作电脑")
    }

    @Test fun directlySelectableSecurityAndAboutViewsAreCaptured() {
        rule.onNodeWithTag("conversation-composer").assertExists()
        rule.onNodeWithTag("composer-permission-control").performClick()
        rule.onNodeWithTag("security-profiles-heading").assertIsDisplayed()
        rule.onNodeWithText("可用权限方案", substring = false).assertExists()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        captureOverlay("audit-security-profiles-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("conversation-composer").assertExists()

        openSettingRoute("about")
        rule.onNodeWithText("关于应用").assertExists()
        captureOverlay("audit-about-android35.png")
    }

    @Test fun permissionProfilesCanBeSelectedAndRestrictedProfilesCannot() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.onNodeWithTag("conversation-composer").assertExists()
        rule.onNodeWithTag("composer-permission-control").performClick()
        rule.waitUntil(15_000L) {
            listOf("composerPermissionConfig", "securityRequirements", "permissionProfiles").none { it in vm.loading.value }
        }
        rule.onNodeWithTag("security-profiles-heading").performScrollTo()
        rule.onNodeWithText("可用权限方案", substring = false).assertExists()
        rule.runOnUiThread {
            vm.data.value = vm.data.value + mapOf(
                "composerPermissionWorkspace" to JSONObject().put("cwd", vm.activeWorkspace.value),
                "composerPermissionConfig" to JSONObject().put("config", JSONObject()
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
        rule.onNodeWithTag("security-profile-workspace").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("security-profile-danger-full-access").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("security-profile-read-only").performScrollTo().assertIsDisplayed().assertIsSelected()
        captureOverlay("audit-security-selection-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("composer-permission-control").assertExists()
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
        rule.onNodeWithTag("screen-title").assertTextEquals("待审批操作")
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
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("context")
        rule.onNodeWithText("创建会话分支").assertDoesNotExist()
        rule.onNodeWithText("整理上下文").assertExists()
        captureOverlay("audit-thread-context-android35.png")
        rule.onNodeWithText("历史回退").performScrollTo().performClick()
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
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("context")
        rule.onNodeWithText("关联资料").performScrollTo().performClick()
        rule.onNodeWithText("添加文本备注").assertExists()
        captureOverlay("audit-thread-attachments-android35.png")
        pressSystemBack()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("review")
        rule.onNodeWithText("审查范围").assertExists()
        rule.onNodeWithContentDescription("返回上一页").assertExists()
        captureOverlay("audit-review-sheet-android35.png")
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("goal")
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
