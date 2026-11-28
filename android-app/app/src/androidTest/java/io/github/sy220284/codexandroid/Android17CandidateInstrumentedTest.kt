package io.github.sy220284.codexandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the explicitly installed target-37 candidate, with real OS permission state. */
@RunWith(AndroidJUnit4::class)
class Android17CandidateInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun localNetworkCardReflectsRealGrant() {
        val argument = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedLanGrant"))
        require(argument == "true" || argument == "false")
        val expected = argument == "true"
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals(37, app.applicationInfo.targetSdkVersion)
        assertTrue(LocalNetworkAccess.applies(app))
        assertEquals(expected, LocalNetworkAccess.granted(app))
        rule.setContent {
            CodexTheme { LocalNetworkAccessCard() }
        }
        rule.onNodeWithTag("settings-local-network").assertIsDisplayed()
        rule.onNodeWithText(if (expected) "已授权" else "未授权").assertIsDisplayed()
        rule.onNodeWithText(if (expected) "打开系统权限设置" else "允许局域网访问").assertIsDisplayed()
    }
}
