package io.github.sy220284.codexandroid

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 单独在横屏小高度与平板宽屏设备尺寸下执行，验证工作区同一条交互链。 */
class UiAdaptiveWindowInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun sidebarComposerAndTabsAdaptWithoutLosingTheTask() {
        val configuration = rule.activity.resources.configuration
        val width = configuration.screenWidthDp.toFloat()
        val height = configuration.screenHeightDp.toFloat()
        val expectedWide = InstrumentationRegistry.getArguments()
            .getString("expect_sidebar", "false").toBooleanStrict()
        val wide = AdaptiveWorkspaceLayout.persistentSidebar(width, height)
        assertEquals(
            "设备尺寸与侧栏布局策略不符：${width}x${height}dp",
            expectedWide, wide
        )
        rule.waitForIdle()
        if (wide) {
            rule.onNodeWithTag("nav-chat").assertExists()
            rule.onNodeWithTag("drawer-toggle").assertDoesNotExist()
        } else {
            rule.onNodeWithTag("drawer-toggle").assertExists()
            rule.onNodeWithContentDescription("打开侧边导航").performClick()
            rule.onNodeWithTag("nav-chat").assertExists()
        }
        rule.onNodeWithTag("nav-chat").performClick()
        rule.onNodeWithTag("conversation-composer").assertExists()
        rule.onNodeWithTag("composer-input").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithTag("tab-chat").assertExists()
        rule.onNodeWithTag("tab-home").performClick()
        rule.onNodeWithTag("home-task-hero").assertExists()
        rule.onNodeWithTag("tab-chat").performClick()
        rule.onNodeWithTag("composer-input").assertIsDisplayed()
    }
}
