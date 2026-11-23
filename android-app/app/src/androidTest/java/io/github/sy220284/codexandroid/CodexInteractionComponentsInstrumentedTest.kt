package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/** 实际模拟器验证：详情不挤动列表、可关闭，上下文菜单能完成所选动作。 */
class CodexInteractionComponentsInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun detailsOpenAsOverlayAndCloseWithoutChangingTheList() {
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            CodexTheme {
                Column {
                    Text("原始列表第一项")
                    CodexDetailTrigger("查看详情", onClick = { open = true })
                    Text("原始列表第二项")
                    if (open) CodexDetailSheet(
                        title = "服务详情",
                        onDismiss = { open = false }
                    ) {
                        Text("详细说明内容")
                        CodexSkeletonRows(2)
                    }
                }
            }
        }
        rule.onNodeWithText("查看详情").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithTag("codex-detail-title").assertTextEquals("服务详情")
        rule.onNodeWithText("详细说明内容").assertExists()
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.onNodeWithText("原始列表第一项").assertExists()
        rule.onNodeWithText("原始列表第二项").assertExists()
    }

    @Test fun contextActionsCloseTheSheetAndRunExactlyOneAction() {
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            var result by remember { mutableStateOf("未操作") }
            CodexTheme {
                Column {
                    Text(result)
                    if (open) CodexActionSheet(
                        title = "文件操作",
                        onDismiss = { open = false },
                        actions = listOf(
                            CodexSheetAction("复制", Icons.Outlined.ContentCopy,
                                onClick = { result = "复制已执行" }),
                            CodexSheetAction("不可用操作", Icons.Outlined.ContentCopy,
                                onClick = { result = "错误调用" }, enabled = false)
                        )
                    )
                }
            }
        }
        rule.onNodeWithText("不可用操作").assertIsNotEnabled()
        rule.onNodeWithText("复制").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.onNodeWithText("复制已执行").assertExists()
    }
}
