package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 参考布局验收：侧栏只出现常用入口，所有原有功能归入设置或快捷菜单。 */
class UiNavigationInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()

    /** Reset process-owned state only after this Activity's navigation shell is ready. */
    @Before fun resetSessionFixture() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000L
        fun remaining() = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        // Activity creation alone does not establish that workspace preparation
        // and the Compose navigation effects have completed.
        try {
            rule.waitUntil(remaining()) {
                rule.onAllNodesWithTag("work-shell").fetchSemanticsNodes().size == 1
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            var diagnostic = ""
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = rule.activity
                diagnostic = "startup=${activity.workspaceStartupPhase}, lifecycle=${activity.lifecycle.currentState}, " +
                    "focus=${activity.hasWindowFocus()}, authorized=${WorkspaceStorage.authorized(activity)}"
            }
            throw AssertionError("导航壳未就绪：$diagnostic", timeout)
        }
        applyAuditTheme()
        rule.waitForIdle()
        val app = rule.activity.application as CodexApplication
        val vm = app.session
        val coldStart = testName.methodName == "coldStartOpensChatAndKeepsDraftSafe"
        // Cold-start coverage must observe the actual initial route, without a
        // synthetic navigation request that could hide a startup regression.
        if (coldStart) {
            rule.waitUntil(remaining()) {
                rule.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes().size == 1
            }
            rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        }
        rule.runOnIdle {
            vm.activeThread.value = null
            vm.activeTurn.value = null
            vm.running.value = false
            vm.threadSwitching.value = false
            vm.timeline.value = emptyList()
            vm.bridge.prompts.value = emptyList()
            vm.composerDrafts.value = emptyMap()
            vm.errors.value = emptyMap()
            app.pendingDeepLink.value = null
            if (!coldStart) vm.agentActionNavigation.value += 1L
        }
        rule.waitUntil(remaining()) {
            vm.agentActionNavigation.value == 0L &&
                rule.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        rule.waitForIdle()
    }

    /** Wait for the actual visible touch target, including platform IME/window motion. */
    private fun stableClick(tag: String, scrollTo: Boolean = false) {
        var last: androidx.compose.ui.geometry.Rect? = null
        var stableSince = 0L
        val target = rule.onNodeWithTag(tag)
        rule.waitUntil(10_000L) {
            val node = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if (node == null || !target.isDisplayed()) {
                last = null
                stableSince = 0L
                if (node != null && scrollTo) target.performScrollTo()
                return@waitUntil false
            }
            val bounds = node.boundsInWindow
            val now = android.os.SystemClock.elapsedRealtime()
            val previous = last
            if (previous == null || kotlin.math.abs(bounds.left - previous.left) > .5f ||
                kotlin.math.abs(bounds.top - previous.top) > .5f || kotlin.math.abs(bounds.right - previous.right) > .5f ||
                kotlin.math.abs(bounds.bottom - previous.bottom) > .5f) { last = bounds; stableSince = now }
            bounds.width > 0 && bounds.height > 0 && now - stableSince >= 300L
        }
        target.assertIsDisplayed().assertIsEnabled().assertHasClickAction().performClick()
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
        // A visible underlying Activity does not yet own input while a
        // dismissing native Dialog still owns the platform window focus.
        rule.waitUntil(10_000L) { rule.activity.window.decorView.hasWindowFocus() }
        stableClick("composer-input")
        input.assertIsFocused()
        assertRealSoftKeyboardOpen()
        input.performTextReplacement("/$command")
        stableClick("composer-shortcut-$command")
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("composer-shortcuts").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("composer-shortcuts").assertDoesNotExist()
        // Insets may briefly report hidden before a queued IME show is dispatched.
        // Require a stable platform acknowledgement before sending the next Back.
        var hiddenSince = 0L
        rule.waitUntil(5_000L) {
            val hidden = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
            val now = android.os.SystemClock.elapsedRealtime()
            if (!hidden) hiddenSince = 0L else if (hiddenSince == 0L) hiddenSince = now
            hidden && now - hiddenSince >= 300L
        }
    }

    private fun waitForConversationAfterSystemBack() {
        try {
            rule.waitUntil(5_000L) { rule.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes().size == 1 }
        } catch (failure: Throwable) {
            val ime = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            throw AssertionError("系统返回未回到会话；IME=$ime；" + rule.onRoot(useUnmergedTree = true).printToString(), failure)
        }
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

    @Test fun duplicatePageIntroUsesTopBarExplanation() {
        openSettingRoute("skills")
        rule.onNodeWithTag("screen-title").assertTextEquals("技能")
        rule.onNodeWithContentDescription("查看技能说明").assertIsDisplayed().performClick()
        rule.onNodeWithText("从 Codex 原生目录读取技能，按技能路径单独启用或停用。").assertIsDisplayed()
        rule.onNodeWithTag("codex-info-close").performClick()
        rule.onNodeWithContentDescription("查看技能说明").assertIsDisplayed()
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
        capture("chat-android.png") { rule.onNodeWithTag("conversation-composer").assertIsDisplayed() }
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("nav-chat").assertDoesNotExist()
        rule.onNodeWithTag("nav-home").assertDoesNotExist()
        rule.onNodeWithTag("nav-settings").assertExists()
        rule.onNodeWithTag("nav-plugins").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertIsDisplayed()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        capture("sidebar-android.png") { rule.onNodeWithTag("sidebar-thread-list").assertIsDisplayed() }
        pressSystemBack()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        rule.onNodeWithText("今天想做什么？").assertExists()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        // A retained drawer must remain inaccessible while offscreen.
        rule.onNodeWithTag("sidebar-thread-list").assertDoesNotExist()
        rule.onNodeWithTag("nav-settings").assertDoesNotExist()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-thread-list").assertIsDisplayed()
        rule.onNodeWithTag("sidebar-clipboard-query").assertDoesNotExist()
        pressSystemBack()
        rule.onNodeWithTag("sidebar-drawer").assertDoesNotExist()
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
        rule.waitUntil(5_000L) { rule.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes().size == 1 }
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
            capture("audit-backup-background-block-android.png") { rule.onNodeWithTag("backup-export").assertIsDisplayed() }
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
            captureVerifiedDisplayScreenshot("audit-backup-in-progress-android.png") { rule.onNodeWithTag("data-transfer-busy-overlay").assertIsDisplayed() }
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
        captureVerifiedDisplayScreenshot("chat-keyboard-open-android.png") { assertRealSoftKeyboardOpen() }
        assertRealSoftKeyboardOpen()
        rule.onNodeWithTag("composer-expand").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithTag("composer-plus").assertIsDisplayed()
        rule.onNodeWithTag("composer-task-options").assertIsDisplayed()
        rule.onNodeWithTag("conversation-model-control").assertIsDisplayed()
        rule.onNodeWithTag("conversation-mode-control").assertIsDisplayed()
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("composer-add-media").assertIsDisplayed()
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
        capture("chat-toolbar-android.png") { rule.onNodeWithTag("composer-task-options").assertIsDisplayed() }
        rule.onNodeWithTag("composer-plus").performClick()
        rule.onNodeWithText("添加内容").assertExists()
        rule.onNodeWithTag("composer-add-media").assertExists()
        rule.onNodeWithTag("composer-add-files").assertExists()
        rule.onNodeWithTag("composer-add-reference").assertExists()
        rule.onNodeWithTag("composer-add-skill").assertExists()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        capture("attachment-sheet-android.png") { rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed() }
        pressSystemBack()
        rule.onNodeWithTag("codex-floating-menu").assertDoesNotExist()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        captureRoute("terminal", "terminal-sheet-android.png")
    }

    @Test fun statusButtonHasStableMeaningAndSeparatesActions() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("composer-task-options").performClick()
        rule.onNodeWithTag("composer-tool-status").performScrollTo().performClick()
        rule.onNodeWithText("运行状态").assertExists()
        rule.onNodeWithText("查看诊断和日志").assertExists()
        capture("engine-status-android.png") { rule.onNodeWithText("查看诊断和日志", substring = false).assertIsDisplayed() }
    }

    @Test fun modelChoiceUsesOneSharedBottomSheet() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        rule.onNodeWithTag("conversation-model-control").performClick()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("composer-effort").assertExists()
        rule.onNodeWithTag("codex-floating-title").assertTextEquals("切换模型")
        capture("model-sheet-android.png") { rule.onNodeWithTag("composer-effort").assertIsDisplayed() }
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
        rule.onNodeWithTag("diff-reader-review").performScrollTo().assertIsDisplayed().assertTextContains("代码审查")
        rule.onNodeWithTag("diff-reader-copy").performScrollTo().assertIsDisplayed().assertTextContains("复制全部差异")
        rule.onNodeWithText("src/main.kt").assertExists()
        captureRoute("diff", "diff-reader-android.png")
    }

    @Test fun projectTerminalAndFilesReturnToConversation() {
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        rule.onNodeWithTag("terminal-console").assertExists()
        pressSystemBack()
        waitForConversationAfterSystemBack()
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
        capture("mode-sheet-android.png") { rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed() }
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
        waitForConversationAfterSystemBack()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("terminal")
        rule.onNodeWithTag("screen-title").assertTextEquals("终端")
        rule.onNodeWithTag("terminal-console").assertExists()
        pressSystemBack()
        waitForConversationAfterSystemBack()
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
    }

    @Test fun settingsOverviewAndLocalBackupCanBeFoundByIntent() {
        openDrawerRoute("settings")
        rule.onNodeWithTag("settings-overview").assertExists()
        rule.onNodeWithTag("settings-search").assertDoesNotExist()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-backup"))
        rule.onNodeWithTag("settings-backup").assertExists()
    }

    @Test fun settingsOpensUnifiedAccountMetricsAndSwitchesQuotaTab() {
        openSettingRoute("usage")
        rule.onNodeWithTag("screen-title").assertTextEquals("用量与额度")
        rule.onNodeWithTag("account-metrics-tab-usage").assertIsSelected()
        rule.onNodeWithText("账号使用概况").assertExists()
        rule.onNodeWithTag("account-metrics-tab-limits").performClick().assertIsSelected()
        rule.onNodeWithText("额度窗口").assertExists()
        rule.onNodeWithTag("account-metrics-tab-usage").performClick().assertIsSelected()
        rule.onNodeWithText("账号使用概况").assertExists()
    }

    @Test fun settingsGroupsAndInlineAppearanceAndUpdate() {
        openDrawerRoute("settings")
        rule.onNodeWithText("账户与模型").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-update"))
        rule.onNodeWithTag("settings-update").assertExists()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("update-check"))
        rule.onNodeWithTag("update-check").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        capture("settings-update-sheet-android.png") { rule.onNodeWithTag("update-check").assertIsDisplayed() }
        captureRoute("settings", "settings-hub-android.png")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-route-appearance"))
        rule.onNodeWithTag("settings-route-appearance").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("外观与显示")
        rule.onNodeWithText("柔和阴影").assertExists()
        rule.onNodeWithText("减少动效").assertExists()
        captureRoute("appearance", "appearance-android.png")
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
        captureRoute("terminal", "terminal-android.png")
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
        capture("terminal-confirm-android.png") { rule.onNodeWithText("确认执行命令", substring = false).assertIsDisplayed() }
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
        captureRoute("account", "account-android.png")
        rule.onNodeWithTag("account-usage-limits").performScrollTo().performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("用量与额度")
    }

    @Test fun featureCatalogKeepsUserFacingRoutesOnly() {
        val widthDp = rule.activity.resources.configuration.screenWidthDp
        val args = InstrumentationRegistry.getArguments()
        val expectedWidth = requireNotNull(args.getString("expectedWidthPx")?.toIntOrNull()) { "缺少实际设备宽度验收参数" }
        val expectedDensity = requireNotNull(args.getString("expectedDensityDpi")?.toIntOrNull()) { "缺少实际设备密度验收参数" }
        assertEquals(expectedWidth, rule.activity.resources.displayMetrics.widthPixels)
        assertEquals(expectedDensity, rule.activity.resources.displayMetrics.densityDpi)
        assertTrue("设备逻辑宽度与像素/密度不符：$widthDp",
            kotlin.math.abs(widthDp - expectedWidth * 160.0 / expectedDensity) <= 1.0)
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        assertEquals(UiFeatureCatalog.all.size, UiFeatureCatalog.byRoute.size)
        assertEquals(pages.filter { it.id != "settings" }.map { it.id }.toSet(),
            UiFeatureCatalog.legacyRoutes.toSet())
        assertEquals(5, NavigationPolicy.featureGroups.size)
        assertTrue(NavigationPolicy.settingsRoutes.all { it in UiFeatureCatalog.byRoute })
        assertTrue(NavigationPolicy.settingsRoutes.containsAll(listOf("account", "providers", "models", "appearance", "config", "diagnostics", "features")))
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
        captureRoute("features", "audit-features-android.png")
        openSettingRoute("config")
        rule.onNodeWithTag("screen-title").assertTextEquals("开发者设置")
        openSettingRoute("diagnostics")
        rule.onNodeWithTag("screen-title").assertTextEquals("诊断与日志")
    }

    @Test fun threadLibraryExposesServerBackedManagementLayout() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("sidebar-management").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-active-tasks").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-sort-filter").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-waiting-tasks").assertDoesNotExist()
        rule.onNodeWithText("会话分组").assertDoesNotExist()
        rule.onNodeWithText("批量管理").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("sidebar-query-filters").assertIsDisplayed().performClick()
        rule.onNodeWithText("会话", substring = false).assertExists()
        rule.onNodeWithText("已归档", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("全文搜索").performScrollTo().assertIsDisplayed()
        capture("audit-sidebar-query-filters-android.png") { rule.onNodeWithTag("sidebar-filters-done").assertIsDisplayed() }
        rule.onNodeWithTag("sidebar-filters-done").assertIsDisplayed().performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertExists()
        rule.onNodeWithTag("sidebar-query-filters").assertTextContains("已归档", substring = true)
        rule.onNodeWithTag("sidebar-new-chat").assertExists()
        rule.onNodeWithTag("thread-library-list").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-management").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-manage-sections").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-active-tasks").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-sort-filter").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-waiting-tasks").assertDoesNotExist()
        rule.onNodeWithText("会话分组").assertDoesNotExist()
        rule.onNodeWithText("批量管理").assertDoesNotExist()
        rule.onNodeWithText("下一页").assertDoesNotExist()
        capture("thread-library-android.png") { rule.onNodeWithTag("sidebar-thread-list").assertIsDisplayed() }
        rule.onNodeWithTag("sidebar-query-filters").performClick()
        rule.onNodeWithText("全文搜索").performScrollTo().performClick()
        rule.onNodeWithTag("sidebar-filters-done").performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertExists()
        capture("thread-full-search-android.png") { rule.onNodeWithTag("sidebar-clipboard-query").assertIsDisplayed() }
    }

    @Test fun accountMetricsUsesSharedUsageAndLimitsTabs() {
        openSettingRoute("account")
        rule.onNodeWithTag("account-usage-limits").performScrollTo().performClick()
        rule.onNodeWithText("账号使用概况").assertExists()
        capture("account-usage-android.png") { rule.onNodeWithText("账号使用概况", substring = false).assertIsDisplayed() }
        rule.onNodeWithText("额度", substring = false).performClick()
        rule.onNodeWithText("额度窗口").assertExists()
        capture("account-limits-android.png") { rule.onNodeWithText("额度窗口", substring = false).assertIsDisplayed() }
    }

    @Test fun workspaceProjectManagerOpensWithoutExpandingTheFileList() {
        openDrawerRoute("files")
        rule.onNodeWithTag("workspace-project-management").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("codex-detail-title").assertTextEquals("项目列表与管理")
        capture("audit-project-manager-sheet-android.png") { rule.onNodeWithTag("codex-detail-title").assertIsDisplayed() }
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
        capture("audit-environment-diagnostics-sheet-android.png") { rule.onNodeWithTag("environment-check-status").assertIsDisplayed() }
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
        rule.onNodeWithTag("workspace-create-kind-folder").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("workspace-new-name").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("workspace-create").assertIsNotEnabled()
        rule.onNodeWithTag("action-reply-input").performScrollTo().assertIsDisplayed().performTextReplacement(name)
        stableClick("action-reply-send", scrollTo = true)
        rule.onNodeWithTag("workspace-new-name").assertTextContains(name)
        rule.onNodeWithTag("workspace-create").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithTag("workspace-new-name").fetchSemanticsNodes().isEmpty()
        }
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
        captureRoute("files", "workspace-files-android.png")
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
        capture("account-sessions-android.png") { rule.onNodeWithText("账号与工作区切换", substring = false).assertIsDisplayed() }
    }

    @Test fun pluginDiscoveryUsesClipboardWithoutSearchField() {
        openSettingRoute("plugins")
        rule.onNodeWithTag("plugins-tab-catalog").performClick().assertIsSelected()
        rule.onNodeWithTag("plugins-clipboard-query").performScrollTo().assertIsDisplayed()
            .assertTextContains("使用剪贴板发现插件")
        rule.onNodeWithText("插件名称或关键字").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        captureRoute("plugins", "plugin-search-android.png")
    }

    @Test fun installedPluginManagementAndCatalogStayReachable() {
        openSettingRoute("plugins")
        rule.onNodeWithTag("plugins-tab-installed").performClick().assertIsSelected()
        captureRoute("plugins", "plugins-android.png")
        rule.onNodeWithTag("plugins-tab-catalog").performClick()
        rule.onNodeWithText("插件源", substring = false).assertExists()
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
            // The presentation is prepared off the Compose idle clock. The
            // lazy viewport may only compose its tail on compact/high-font layouts.
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithTag("chat-activity-group").fetchSemanticsNodes().isNotEmpty()
            }
            val viewport = rule.onNodeWithTag("conversation-timeline", useUnmergedTree = true)
                .assertIsDisplayed()
            viewport.performScrollToNode(hasTestTag("chat-user-message"))
            rule.onNodeWithTag("chat-user-message").assertIsDisplayed()
            rule.onNodeWithText("检查项目结构").assertIsDisplayed()
            rule.onNodeWithTag("chat-user-prompt-marker", useUnmergedTree = true).assertDoesNotExist()
            viewport.performScrollToNode(hasTestTag("chat-assistant-message"))
            rule.onNodeWithTag("chat-assistant-message").assertIsDisplayed()
            rule.onNodeWithText("已找到项目入口").assertIsDisplayed()
            viewport.performScrollToNode(hasTestTag("message-copy-ui-agent"))
            rule.onNodeWithTag("message-copy-ui-agent").assertExists()
            rule.onNodeWithTag("message-retry-ui-agent").assertExists()
            rule.onNodeWithTag("message-share-ui-agent").assertExists()
            viewport.performScrollToNode(hasTestTag("chat-activity-group"))
            rule.onNodeWithTag("chat-activity-group").assertIsDisplayed().performClick()
            viewport.performScrollToNode(hasText("pwd"))
            rule.onNodeWithText("pwd").assertIsDisplayed()
            rule.onNodeWithTag("composer-send").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { vm.timeline.value = emptyList() }
        }
    }

    @Test fun progressiveLongReplyKeepsReadingAnchorAndReturnsToTail() {
        val vm = (rule.activity.application as CodexApplication).session
        val history = (0 until 36).map { i ->
            TimelineItem("scroll-history-$i", "agentMessage", "历史阅读锚点 $i", "completed", turnId = "scroll-turn-$i")
        }
        val tail = TimelineItem("scroll-tail", "agentMessage", "当前阶段成果", "inProgress", turnId = "scroll-live")
        try {
            rule.runOnUiThread { vm.timeline.value = history + tail }
            // Presentation preparation runs outside Compose's idle clock. Wait
            // for its real tail, then address the LazyColumn's own scroll node.
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithText("当前阶段成果").fetchSemanticsNodes().isNotEmpty()
            }
            val viewport = rule.onNodeWithTag("conversation-timeline", useUnmergedTree = true)
                .assertIsDisplayed()
            viewport.performScrollToNode(hasText("历史阅读锚点 12"))
            rule.waitForIdle()
            val anchor = rule.onNodeWithText("历史阅读锚点 12")
            anchor.assertIsDisplayed()
            // Real touch input takes ownership of the viewport, rather than relying on a programmatic jump.
            viewport.performTouchInput { swipeDown(startY = centerY, endY = centerY + 100f, durationMillis = 500) }
            rule.waitForIdle()
            val visible = history.map { it.text }.firstOrNull { label ->
                rule.onNodeWithText(label).isDisplayed()
            } ?: error("未取得真实可见的阅读锚点")
            val reading = rule.onNodeWithText(visible)
            reading.assertIsDisplayed()
            val before = reading.fetchSemanticsNode().boundsInRoot.top
            repeat(12) { n ->
                rule.runOnUiThread { vm.timeline.value = history + tail.copy(text = "当前阶段成果 " + "增量内容 ".repeat(n + 1)) }
                rule.waitForIdle()
            }
            reading.assertIsDisplayed()
            val after = reading.fetchSemanticsNode().boundsInRoot.top
            assertTrue("连续回复期间阅读位置偏移：$before -> $after", kotlin.math.abs(before - after) <= 2f)
            // New message identities must not push the history away (Jetchat #696).
            val updated = tail.copy(text = "当前阶段成果 " + "增量内容 ".repeat(12))
            val appended = TimelineItem("scroll-next", "agentMessage", "新增独立步骤", "inProgress", turnId = "scroll-next-turn")
            rule.runOnUiThread { vm.timeline.value = history + updated + appended }
            rule.waitUntil(10_000L) { rule.onAllNodesWithText("↓ 1 条新步骤").fetchSemanticsNodes().isNotEmpty() }
            reading.assertIsDisplayed()
            assertTrue("新增消息改变历史阅读位置", kotlin.math.abs(reading.fetchSemanticsNode().boundsInRoot.top - before) <= 2f)
            rule.onNodeWithTag("timeline-return-latest").assertIsDisplayed().performClick()
            rule.waitUntil(10_000L) { rule.onAllNodesWithText("新增独立步骤").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("新增独立步骤").assertIsDisplayed()
            rule.runOnUiThread { vm.timeline.value = history + updated + appended.copy(text = "最终交付已收到", status = "completed") }
            rule.waitForIdle()
            rule.onNodeWithText("最终交付已收到").assertIsDisplayed()
            // Completing a progressive turn must keep the coordinate origin of the fragment being read.
            val partial = TimelineItem("anchor-partial", "agentMessage", "第一阶段长内容\n\n".repeat(120),
                "completed", turnId = "anchor-turn", phase = "partial_answer")
            val final = TimelineItem("anchor-final", "agentMessage",
                (0 until 80).joinToString("\n\n") { "第二阶段阅读位置 $it" }, "inProgress",
                turnId = "anchor-turn", phase = "final_answer")
            rule.runOnUiThread { vm.timeline.value = listOf(partial, final) }
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithText("第二阶段阅读位置", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // A logical message can span several lazy pages. Find the actual
            // final fragment instead of treating logical row 1 as physical page 1.
            viewport.performScrollToNode(hasText("第二阶段阅读位置 0"))
            viewport.performTouchInput { swipeUp() }
            rule.waitForIdle()
            val fragmentLabel = (0 until 80).map { "第二阶段阅读位置 $it" }.first { label ->
                rule.onNodeWithText(label).isDisplayed()
            }
            val fragment = rule.onNodeWithText(fragmentLabel)
            val fragmentBefore = fragment.fetchSemanticsNode().boundsInRoot.top
            rule.runOnUiThread {
                vm.timeline.value = TimelineEventReducer.reduce("turn/completed", org.json.JSONObject().put("turn",
                    org.json.JSONObject().put("id", "anchor-turn").put("status", "completed")), vm.timeline.value)
            }
            rule.waitForIdle()
            fragment.assertIsDisplayed()
            assertTrue("回合结束改变了片段阅读位置", kotlin.math.abs(fragment.fetchSemanticsNode().boundsInRoot.top - fragmentBefore) <= 2f)
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
                captureRoute(route, "audit-" + route + "-android.png")
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
        val text = "保留未发送的队列内容"
        val field = rule.onNodeWithTag("thread-queue-draft")
        stableClick("thread-queue-draft")
        field.assertIsFocused().performTextInput(text)
        field.assertTextContains(text)
        rule.runOnIdle {
            assertEquals("输入必须先进入原会话的草稿", text, vm.taskPanelDraft("ci-queue-draft").queueText)
        }
        // Close through the real owning window while its text field has focus.
        // Do not send IME commands to the underlying Activity's window token.
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-draft").fetchSemanticsNodes().isEmpty()
        }
        rule.runOnIdle {
            assertEquals("关闭窗口不得清除草稿", text, vm.taskPanelDraft("ci-queue-draft").queueText)
        }
        openComposerShortcut("queue")
        field.assertTextContains(text)
        // Finish the real navigation lifecycle before the next Activity is launched.
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithTag("thread-queue-draft").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
    }

    @Test fun queueAndFullSearchOpenAsTaskSheets() {
        val vm = (rule.activity.application as CodexApplication).session
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-global-search").performClick()
        rule.onNodeWithTag("sidebar-thread-list").assertExists()
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-query-filters").performClick()
        rule.onNodeWithText("全文搜索").performScrollTo().performClick()
        rule.onNodeWithTag("sidebar-filters-done").performClick()
        rule.onNodeWithTag("sidebar-clipboard-query").assertExists()
        capture("thread-global-search-android.png") { rule.onNodeWithTag("sidebar-clipboard-query").assertIsDisplayed() }
        pressSystemBack()

        rule.runOnUiThread { vm.activeThread.value = "ci-queue-thread" }
        rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
        openComposerShortcut("queue")
        rule.onNodeWithTag("screen-title").assertTextEquals("任务队列")
        rule.onNodeWithTag("thread-queue-draft").assertExists()
        rule.onNodeWithText("后续指令").assertExists()
        assertTrue("同一队列请求错误只显示一次",
            rule.onAllNodesWithText("Codex 内核尚未连接").fetchSemanticsNodes().size <= 1)
        capture("audit-queue-android.png") { rule.onNodeWithTag("thread-queue-draft").assertIsDisplayed() }
        pressSystemBack()
        waitForConversationAfterSystemBack()
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
                rule.onNodeWithTag("diagnostics-overview").assertIsDisplayed()
                rule.onNodeWithTag("diagnostics-connect").assertHasClickAction()
                rule.onNodeWithTag("diagnostics-restart").assertHasClickAction()
                rule.onNodeWithTag("diagnostics-runtime-policy").assertExists()
                rule.onNodeWithTag("diagnostics-log-panel").assertExists()
                rule.onNodeWithText("查看启动日志").assertHasClickAction()
                captureRoute("diagnostics", "audit-diagnostics-android.png")
                rule.onNodeWithTag("diagnostics-environment").performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("diagnostics-crash-history").performScrollTo().assertIsDisplayed()
            } else captureRoute(route, "audit-" + route + "-android.png")
        }
    }

    @Test fun engineDiagnosticsLogPreviewAndFullReaderUseSameSnapshot() {
        openSettingRoute("diagnostics")
        val vm = (rule.activity.application as CodexApplication).session
        val previous = vm.bridge.recentStderr.value
        try {
            rule.runOnUiThread { vm.bridge.recentStderr.value = emptyList() }
            rule.onNodeWithTag("diagnostics-log-copy").assertIsNotEnabled()
            rule.onNodeWithTag("diagnostics-log-share").assertIsNotEnabled()
            rule.runOnUiThread {
                vm.bridge.recentStderr.value = (1..8).map { "诊断测试行 " + it }
            }
            rule.onNodeWithTag("diagnostics-log-preview").performScrollTo()
                .assertTextContains("诊断测试行 8", substring = true)
            rule.onNodeWithTag("diagnostics-log-copy").assertIsEnabled()
            rule.onNodeWithTag("diagnostics-log-share").assertIsEnabled()
            rule.onNodeWithText("查看启动日志").performScrollTo().performClick()
            rule.onNodeWithTag("full-text-reader").assertExists()
            rule.onNodeWithTag("full-text-chunk-0").assertTextContains("诊断测试行 1", substring = true)
            pressSystemBack()
            rule.onNodeWithTag("full-text-reader").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { vm.bridge.recentStderr.value = previous }
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
        captureRoute("approvals", "audit-approvals-android.png")
        pressSystemBack()
        rule.runOnUiThread { vm.bridge.prompts.value = emptyList() }

        openSettingRoute("providers")
        rule.onNodeWithText("添加自定义服务商").performScrollTo().performClick()
        rule.onNodeWithTag("provider-endpoint").assertExists()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        capture("audit-provider-editor-android.png") { rule.onNodeWithTag("provider-endpoint").assertIsDisplayed() }
        rule.onNodeWithTag("codex-detail-close").performClick()

        openSettingRoute("config")
        rule.onNodeWithTag("config-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("config-agent-action").assertExists()
        rule.onNodeWithText("设置项名称").assertDoesNotExist()
        rule.onNodeWithText("高级数据").assertDoesNotExist()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        capture("audit-config-editor-android.png") { rule.onNodeWithTag("config-agent-action").assertIsDisplayed() }
        rule.onNodeWithTag("codex-detail-close").performClick()

        openSettingRoute("environments")
        rule.onNodeWithTag("environment-remote-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-remote-name").assertDoesNotExist()
        rule.onNodeWithTag("environment-endpoint").assertExists()
        captureRoute("environments", "audit-environment-editor-android.png")
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
        rule.onNodeWithTag("environment-endpoint").performScrollTo()
        stableClick("environment-endpoint")
        rule.onNodeWithTag("environment-address-reply-input").performScrollTo().performTextReplacement("wss://example.com/exec")
        rule.onNodeWithTag("environment-address-reply-send").assertIsEnabled()
        rule.onNodeWithTag("environment-address-reply-input").performImeAction()
        rule.waitUntil(5_000L) { rule.onAllNodesWithTag("environment-remote-name").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("environment-remote-name").assertTextContains("example.com", substring = true)
        rule.onNodeWithTag("environment-remote-name").performScrollTo()
        stableClick("environment-remote-name")
        rule.onNodeWithTag("environment-connect").assertIsNotEnabled()
        rule.onNodeWithTag("environment-name-reply-input").performScrollTo().performTextReplacement("工作电脑")
        rule.onNodeWithTag("environment-name-reply-send").assertIsEnabled()
        rule.onNodeWithTag("environment-name-reply-input").performImeAction()
        rule.waitUntil(5_000L) { rule.onAllNodesWithTag("environment-name-reply-input").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("environment-remote-name").assertTextContains("工作电脑")
        rule.onNodeWithTag("environment-connect").assertIsEnabled()

        rule.onNodeWithTag("environment-local-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-endpoint").assertDoesNotExist()
        rule.onNodeWithTag("environment-local-option").assertExists()
        rule.onNodeWithTag("environment-remote-option").performScrollTo().performClick()
        rule.onNodeWithTag("environment-endpoint").assertTextContains("wss://example.com/exec")
        rule.onNodeWithTag("environment-remote-name").assertTextContains("工作电脑")
        // Editing the address must not overwrite a user-chosen name.
        rule.onNodeWithTag("environment-endpoint").performScrollTo()
        stableClick("environment-endpoint")
        rule.onNodeWithTag("environment-address-reply-input").performScrollTo().performTextReplacement("wss://other.example/exec")
        rule.onNodeWithTag("environment-address-reply-send").assertIsEnabled()
        rule.onNodeWithTag("environment-address-reply-input").performImeAction()
        rule.onNodeWithTag("environment-remote-name").assertTextContains("工作电脑")
        // Cancel keeps the existing name; explicitly clearing it restores the derived name.
        rule.onNodeWithTag("environment-remote-name").performScrollTo()
        stableClick("environment-remote-name")
        rule.onNodeWithTag("environment-name-reply-input").performScrollTo().performTextReplacement("临时名称")
        // The system owns Back while its keyboard is open. Dismiss it before scrolling
        // to the separate cancel control; dismissal must preserve the in-progress reply.
        assertRealSoftKeyboardOpen()
        pressSystemBack()
        rule.waitUntil(5_000L) {
            androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        rule.onNodeWithTag("environment-name-reply-input").assertTextContains("临时名称")
        rule.onNodeWithTag("environment-name-reply-cancel").performScrollTo()
        stableClick("environment-name-reply-cancel")
        rule.onNodeWithTag("environment-remote-name").assertTextContains("工作电脑")
        rule.onNodeWithTag("environment-remote-name").performScrollTo()
        stableClick("environment-remote-name")
        rule.onNodeWithTag("environment-name-reply-input").performScrollTo().performTextClearance()
        rule.onNodeWithTag("environment-name-reply-send").assertIsEnabled()
        rule.onNodeWithTag("environment-name-reply-input").performImeAction()
        rule.onNodeWithTag("environment-remote-name").assertTextContains("other.example", substring = true)
        rule.onNodeWithTag("environment-connect").assertIsEnabled()
    }

    @Test fun directlySelectableSecurityAndAboutViewsAreCaptured() {
        rule.onNodeWithTag("conversation-composer").assertExists()
        rule.onNodeWithTag("composer-permission-control").performClick()
        rule.onNodeWithTag("security-profiles-heading").assertIsDisplayed()
        rule.onNodeWithText("可用权限方案", substring = false).assertExists()
        rule.onNodeWithTag("codex-floating-menu").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        capture("audit-security-profiles-android.png") { rule.onNodeWithTag("security-profiles-heading").assertIsDisplayed() }
        pressSystemBack()
        waitForConversationAfterSystemBack()
        rule.onNodeWithTag("conversation-composer").assertExists()

        openSettingRoute("about")
        rule.onNodeWithText("关于应用").assertExists()
        capture("audit-about-android.png") { rule.onNodeWithText("关于应用", substring = false).assertIsDisplayed() }
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
        capture("audit-security-selection-android.png") { rule.onNodeWithTag("security-profile-read-only").assertIsDisplayed() }
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
        captureRoute("approvals", "audit-permission-request-android.png")
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
        capture("audit-thread-context-android.png") { rule.onNodeWithText("整理上下文", substring = false).assertIsDisplayed() }
        rule.onNodeWithText("历史回退").performScrollTo().performClick()
        rule.waitUntil(5_000L) {
            rule.onAllNodesWithText("查看完整时间线（实验）").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("查看完整时间线（实验）").assertExists()
        capture("audit-thread-history-android.png") { rule.onNodeWithText("查看完整时间线（实验）", substring = false).assertIsDisplayed() }
        rule.onNodeWithText("查看完整时间线（实验）").performClick()
        rule.onNodeWithText("会话时间线").assertExists()
        capture("audit-advanced-timeline-android.png") { rule.onNodeWithText("会话时间线", substring = false).assertIsDisplayed() }
        rule.onNodeWithText("查看完整时间线（实验）").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回回合历史").performClick()
        rule.onNodeWithText("会话时间线").assertDoesNotExist()
        rule.onNodeWithText("查看完整时间线（实验）").assertIsDisplayed()
        rule.onNodeWithText("查看完整时间线（实验）").performClick()
        rule.onNodeWithText("会话时间线").assertIsDisplayed()
        pressSystemBack()
        rule.onNodeWithText("会话时间线").assertDoesNotExist()
        rule.onNodeWithText("查看完整时间线（实验）").assertIsDisplayed()
        pressSystemBack()
        rule.onNodeWithText("查看完整时间线（实验）").assertDoesNotExist()
        rule.onNodeWithTag("conversation-top-bar").assertIsDisplayed()
        openComposerShortcut("context")
        rule.onNodeWithText("关联资料").performScrollTo().performClick()
        rule.onNodeWithText("添加文本备注").assertExists()
        capture("audit-thread-attachments-android.png") { rule.onNodeWithText("添加文本备注", substring = false).assertIsDisplayed() }
        pressSystemBack()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("review")
        rule.onNodeWithText("审查范围").assertExists()
        rule.onNodeWithContentDescription("返回上一页").assertExists()
        captureRoute("review", "audit-review-sheet-android.png")
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("conversation-top-bar").assertExists()
        openComposerShortcut("goal")
        rule.onNodeWithText("设置新目标").assertExists()
        rule.onNodeWithTag("goal-sticky-save").assertExists()
        rule.onNodeWithContentDescription("关闭任务目标").assertExists()
        capture("audit-goals-sheet-android.png") { rule.onNodeWithTag("goal-sticky-save").assertIsDisplayed() }
        rule.onNodeWithTag("goal-advanced-toggle").performScrollTo().performClick()
        rule.onNodeWithTag("codex-edit-dialog").assertExists()
        capture("audit-goal-budget-editor-android.png") { rule.onNodeWithTag("codex-edit-dialog").assertIsDisplayed() }
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithTag("codex-edit-dialog").assertDoesNotExist()
        rule.onNodeWithTag("goal-sticky-save").assertIsDisplayed()
        rule.onNodeWithTag("goal-advanced-toggle").performScrollTo().assertIsDisplayed()
    }

    @Test fun sidebarProjectContextOpensFilesWithoutHistoryPicker() {
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithTag("sidebar-project-context").assertExists()
        rule.onNodeWithTag("sidebar-project-context").performClick()
        rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
        rule.onNodeWithTag("thread-library-sheet").assertDoesNotExist()
    }

    @Test fun experienceComposerTargetsAreUnclippedAndAligned() {
        val quality = UiExperienceAssertions(rule)
        quality.bounds("composer-send", 48f, 48f)
        quality.bounds("composer-plus", 48f, 48f)
        quality.bounds("composer-task-options", 48f, 48f)
        quality.bounds("composer-input", 120f)
        quality.nonOverlap("composer-plus", "composer-send", "composer-input")
        quality.alignedCenters("composer-plus", "composer-send")
    }

    @Test fun experienceTopBarTargetsAreIndependent() {
        val quality = UiExperienceAssertions(rule)
        quality.bounds("drawer-toggle", 48f, 48f)
        quality.bounds("top-new-chat", 48f, 48f)
        quality.nonOverlap("drawer-toggle", "top-new-chat")
        quality.alignedCenters("drawer-toggle", "top-new-chat")
        rule.onNodeWithTag("drawer-toggle").performClick()
        quality.bounds("sidebar-global-search", 48f, 48f)
        quality.bounds("nav-settings", 48f, 48f)
        quality.bounds("nav-account", 48f, 48f)
        quality.nonOverlap("nav-settings", "nav-account")
        quality.alignedCenters("nav-settings", "nav-account")
    }

    @Test fun experienceKeyboardDoesNotCoverActualActionBounds() {
        rule.onNodeWithTag("composer-input").performClick()
        rule.onNodeWithTag("composer-input").performTextInput("检查真实键盘遮挡")
        assertRealSoftKeyboardOpen()
        val quality = UiExperienceAssertions(rule)
        quality.visibleAboveIme("composer-input", "composer-plus", "composer-send", "composer-task-options")
        quality.nonOverlap("composer-input", "composer-plus", "composer-send")
        quality.bounds("composer-send", 48f, 48f)
    }

    @Test fun experiencePageTitleTypographyRemainsReadable() {
        val quality = UiExperienceAssertions(rule)
        openSettingRoute("providers")
        quality.untruncatedText("screen-title", "模型服务商")
        rule.onNodeWithContentDescription("返回上一页").performClick()
        rule.onNodeWithTag("settings-list").assertIsDisplayed()
        quality.untruncatedText("screen-title", "设置")
    }

    @Test fun experienceDraftSurvivesRepeatedWorkspaceRoundTrips() {
        val draft = "需要保留的实际任务草稿"
        rule.onNodeWithTag("composer-input").performClick()
        rule.onNodeWithTag("composer-input").performTextReplacement(draft)
        assertRealSoftKeyboardOpen()
        pressSystemBack()
        repeat(2) {
            openDrawerRoute("files")
            rule.onNodeWithTag("screen-title").assertTextEquals("工作区文件")
            pressSystemBack()
            rule.onNodeWithTag("conversation-composer").assertIsDisplayed()
            rule.onNodeWithTag("composer-input").assertTextContains(draft)
            // This UI-only APK has no native runtime; preserve its real offline send gate.
            rule.onNodeWithTag("composer-send").assertIsNotEnabled()
        }
    }

    private fun captureRoute(route: String, fileName: String) {
        rule.waitForIdle()
        fun assertRoute() {
            rule.onNodeWithTag("work-route-" + route, useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithTag("screen-title").assertIsDisplayed()
                .assertTextEquals(pages.first { it.id == route }.title)
        }
        // Navigation is a functional assertion. The screenshot's own before/after
        // checks reject mismatched evidence without rewriting the functional result.
        assertRoute()
        captureVerifiedDisplayScreenshot(fileName, ::assertRoute)
    }

    private fun capture(fileName: String, assertSubject: () -> Unit) {
        rule.waitForIdle()
        captureVerifiedDisplayScreenshot(fileName, assertSubject)
    }
}

