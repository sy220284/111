package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals

/** Invoked from the existing instrumented suite so every width executes these scenarios. */
internal fun assertControlSystemInteraction(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule) {
    var actions = 0
    rule.setContent {
        CodexTheme {
            var chosen by remember { mutableStateOf("file") }
            var name by remember { mutableStateOf("draft") }
            Column(Modifier.selectableGroup()) {
                AtlasExclusiveChip(chosen == "file", { chosen = "file" }, { Text("文件") },
                    modifier = Modifier.testTag("v7-file"))
                AtlasExclusiveChip(chosen == "folder", { chosen = "folder" }, { Text("文件夹") },
                    modifier = Modifier.testTag("v7-folder"))
                ActionQuestionRow(name, { name = it }, "名称", modifier = Modifier.testTag("v7-field"))
                CodexSecondaryButton(onClick = { actions++ }, enabled = name.isNotBlank(),
                    modifier = Modifier.testTag("v7-save")) { Text("保存") }
                CodexTextAction(onClick = { actions += 10 }, enabled = false,
                    modifier = Modifier.testTag("v7-disabled")) { Text("禁用") }
            }
        }
    }
    rule.onNodeWithTag("v7-file").assertIsSelected()
    rule.onNodeWithTag("v7-folder").performClick()
    rule.onNodeWithTag("v7-folder").assertIsSelected()
    rule.onNodeWithTag("v7-file").assertIsNotSelected()
    rule.onNodeWithTag("v7-field").performClick()
    rule.onNodeWithTag("action-reply-input").performTextClearance()
    rule.onNodeWithTag("action-reply-send").performClick()
    rule.onNodeWithTag("v7-save").assertIsNotEnabled()
    rule.onNodeWithTag("v7-field").performClick()
    rule.onNodeWithTag("action-reply-input").performTextInput("new")
    rule.onNodeWithTag("action-reply-send").performClick()
    rule.onNodeWithTag("v7-save").assertIsEnabled().performClick()
    rule.onNodeWithTag("v7-disabled").assertIsNotEnabled()
    rule.runOnIdle { assertEquals(1, actions) }
}
