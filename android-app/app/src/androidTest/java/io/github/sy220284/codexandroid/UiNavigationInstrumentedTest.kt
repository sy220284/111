package io.github.sy220284.codexandroid

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** UI navigation test runs without a Codex binary; backend unavailability is a valid empty state. */
class UiNavigationInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawerOpensAndNavigatesToConversation() {
        rule.onNodeWithText("让代码，流动起来").assertExists()
        rule.onNodeWithContentDescription("打开侧边导航").performClick()
        rule.onNodeWithText("任务对话").performClick()
        rule.onNodeWithText("新的工作会话").assertExists()
        rule.onNodeWithText("从一个具体目标开始").assertExists()
    }
}
