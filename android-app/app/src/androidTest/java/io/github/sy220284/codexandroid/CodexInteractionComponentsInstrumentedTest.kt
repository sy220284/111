package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
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

    @Test fun sharedSearchSubmitsAndClearsWithoutLeakingOldQuery() {
        rule.setContent {
            var query by remember { mutableStateOf("") }
            var output by remember { mutableStateOf("未搜索") }
            CodexTheme {
                Column {
                    AtlasSearchField(
                        value = query, onValueChange = { query = it },
                        placeholder = "查找任务",
                        modifier = androidx.compose.ui.Modifier.testTag("atlas-search-field"),
                        onSubmit = { output = "已搜索：" + query },
                        onClear = { query = ""; output = "已清空" },
                        submitTestTag = "atlas-search-submit"
                    )
                    Text(output)
                }
            }
        }
        rule.onNodeWithTag("atlas-search-submit").assertIsNotEnabled()
        rule.onNodeWithTag("atlas-search-field").performTextInput("新任务")
        rule.onNodeWithTag("atlas-search-submit").assertIsEnabled().performClick()
        rule.onNodeWithText("已搜索：新任务").assertExists()
        rule.onNodeWithContentDescription("清除搜索词").performClick()
        rule.onNodeWithText("已清空").assertExists()
        rule.onNodeWithTag("atlas-search-submit").assertIsNotEnabled()
    }

    @Test fun statusSelectorShowsActualSelectedTaskStatus() {
        rule.setContent {
            var status by remember { mutableStateOf("active") }
            CodexTheme {
                Column {
                    AtlasStatusSelector(
                        selected = status,
                        options = listOf(
                            AtlasStatusOption("active", "进行中", StatusTone.Info),
                            AtlasStatusOption("paused", "已暂停", StatusTone.Neutral),
                            AtlasStatusOption("blocked", "遇到阻碍", StatusTone.Warning),
                            AtlasStatusOption("complete", "已完成", StatusTone.Success)
                        ),
                        onSelect = { status = it }
                    )
                    Text("已选择：" + status)
                    AtlasSequenceBadge(2, 4)
                }
            }
        }
        rule.onNodeWithText("进行中").assertExists()
        rule.onNodeWithText("遇到阻碍").performClick()
        rule.onNodeWithText("已选择：blocked").assertExists()
        rule.onNodeWithContentDescription("第2 项，共 4 项").assertExists()
    }

    @Test fun loadingAndValidationFeedbackDescribeRealState() {
        rule.setContent {
            CodexTheme {
                Column {
                    AtlasOperationProgress("正在导入文件", progress = .4f)
                    AtlasFieldFeedback("请输入有效的文件名", isError = true)
                    AtlasLoadingResults("正在检索历史记录", placeholderRows = 2)
                }
            }
        }
        rule.onNodeWithText("正在导入文件").assertExists()
        rule.onNodeWithText("40%").assertExists()
        rule.onNodeWithText("请输入有效的文件名").assertExists()
        rule.onNodeWithTag("atlas-loading-results").assertExists()
    }

    @Test fun settingActionCardRespondsWhenTappingTextArea() {
        rule.setContent {
            var opened by remember { mutableStateOf(false) }
            CodexTheme {
                Column {
                    AtlasSettingsActionRow(
                        title = "查看服务能力",
                        detail = "读取实际可用功能",
                        icon = Icons.Outlined.Settings,
                        modifier = androidx.compose.ui.Modifier.testTag("atlas-settings-action"),
                        onClick = { opened = true }
                    )
                    Text(if (opened) "已打开" else "未打开")
                }
            }
        }
        rule.onNodeWithTag("atlas-settings-action").performClick()
        rule.onNodeWithText("已打开").assertExists()
    }

    @Test fun actionDialogInsideParentSheetDoesNotDuplicateBottomSheets() {
        rule.setContent {
            var parentVisible by remember { mutableStateOf(true) }
            var menuVisible by remember { mutableStateOf(false) }
            var performed by remember { mutableStateOf("未执行") }
            CodexTheme {
                Column {
                    Text(performed)
                    if (parentVisible) CodexDetailSheet(
                        title = "项目管理",
                        onDismiss = { parentVisible = false }
                    ) {
                        TextButton(onClick = { menuVisible = true }) {
                            Text("项目更多操作")
                        }
                    }
                    if (menuVisible) CodexActionDialog(
                        title = "管理当前项目",
                        onDismiss = { menuVisible = false },
                        actions = listOf(
                            CodexSheetAction(
                                label = "重命名",
                                icon = Icons.Outlined.Settings,
                                onClick = { performed = "已进入重命名" }
                            ),
                            CodexSheetAction(
                                label = "不可移动",
                                icon = Icons.Outlined.Settings,
                                enabled = false,
                                onClick = { performed = "不应执行" }
                            )
                        )
                    )
                }
            }
        }
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithText("项目更多操作").performClick()
        rule.onNodeWithText("不可移动").assertIsNotEnabled()
        rule.onNodeWithText("重命名").performClick()
        rule.onNodeWithText("已进入重命名").assertExists()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.onNodeWithText("管理当前项目").assertDoesNotExist()
    }
}
