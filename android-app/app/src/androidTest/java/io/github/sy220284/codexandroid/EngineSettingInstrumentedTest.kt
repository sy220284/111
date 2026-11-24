package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EngineSettingInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun engineSettingsUsesReadableChoicesAndSavesNativeValue() {
        val setting = EngineSettings.byKey("model_verbosity")!!
        var submitted: Any? = null
        rule.setContent {
            CodexTheme {
                EngineSettingEditor(setting, "medium", setting.choices, true, false,
                    onDismiss = {}, onSave = { submitted = it })
            }
        }
        rule.onNodeWithText("当前配置：适中").assertExists()
        rule.onNodeWithTag("engine-setting-save").assertIsNotEnabled()
        rule.onNodeWithTag("engine-option-high").performClick()
        rule.onNodeWithTag("engine-setting-save").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("high", submitted) }
    }
}
