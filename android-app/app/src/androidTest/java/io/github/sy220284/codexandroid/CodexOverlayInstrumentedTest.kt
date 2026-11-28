package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Verifies a real Material 3 overlay, including explicit dismiss without side effects. */
class CodexOverlayInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun clipboardOverlayIsDetectedEvenWhenAppRetainsFocus() {
        val app = "mCurrentFocus=Window{abc u0 io.github.sy220284.codexandroid/.MainActivity}\n" +
            "  Window #1 Window{abc u0 io.github.sy220284.codexandroid/.MainActivity}:\n"
        assertFalse(hasClipboardOverlayWindow(app))
        assertTrue(hasClipboardOverlayWindow(app + "  Window #2 Window{def u0 ClipboardOverlay}:\n"))
        assertFalse(hasClipboardOverlayWindow(app + "  Window #2 Window{def u0 InputMethod}:\n"))
    }

    @Test fun longDetailContextScrollsWithoutDisplacingFooterOrClose() {
        var accepted = 0
        var closed by mutableStateOf(false)
        val title = "带有较长名称的服务来源与安装权限详情".repeat(4)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CodexTheme {
                    if (!closed) CodexDetailSheet(title, onDismiss = { closed = true },
                        subtitle = "安装前请核对服务身份、来源与实际权限范围。\n".repeat(25),
                        footer = {
                            CodexPrimaryButton(onClick = { accepted++ },
                                modifier = Modifier.fillMaxWidth().testTag("long-detail-confirm")) { Text("确认选择") }
                        }) { Text("正文最后一项") }
                }
            }
        }
        rule.onNodeWithTag("codex-detail-close").assertIsDisplayed()
        rule.onNodeWithTag("long-detail-confirm").assertIsDisplayed().performClick()
        rule.onNodeWithText("正文最后一项").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("long-detail-confirm").assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-close").assertIsDisplayed()
        captureVerifiedDisplayScreenshot("audit-detail-long-context-android.png")
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, accepted) }
    }

    @Test fun detailCloseCanBeRetriedAfterBusyOperationCompletes() {
        var blocked by mutableStateOf(true)
        var visible by mutableStateOf(true)
        rule.setContent {
            CodexTheme {
                if (visible) CodexDetailSheet("正在处理", onDismiss = { if (!blocked) visible = false }) {
                    Text("等待业务操作结束")
                }
            }
        }
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertExists()
        rule.runOnIdle { blocked = false }
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
    }

    @Test fun sidebarFilterPanelKeepsQueryAndSelectionWhenReopened() {
        val state = SidebarUiState().apply { search = "登录" }
        rule.setContent { CodexTheme { SidebarQueryFilters(state) } }
        rule.onNodeWithText("全文搜索").assertDoesNotExist()
        rule.onNodeWithTag("sidebar-query-filters").performClick()
        rule.onNodeWithTag("sidebar-filter-query-full").performScrollTo().performClick()
        rule.onNodeWithTag("sidebar-filter-status-archived").performScrollTo().performClick()
        rule.onNodeWithTag("sidebar-filter-project-project").performScrollTo().performClick()
        rule.onNodeWithTag("sidebar-filters-done").assertIsDisplayed().performClick()
        rule.onNodeWithTag("sidebar-query-filters").assertTextContains("全文 · 已归档 · 当前项目")
        rule.onNodeWithTag("sidebar-query-filters").performClick()
        rule.onNodeWithTag("sidebar-filter-query-full").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("sidebar-filter-status-archived").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("sidebar-filter-project-project").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("codex-detail-close").performClick()
        rule.runOnIdle { assertEquals("登录", state.search) }
    }

    @Test
    fun dataArchiveExclusiveDialogAppearsAndClearsWithoutLosingPage() {
        val transfer = mutableStateOf(DataTransferState())
        rule.setContent {
            CodexTheme {
                Column {
                    androidx.compose.material3.Text("原有项目编辑状态")
                    DataTransferBusyOverlay(transfer.value)
                }
            }
        }
        rule.onNodeWithText("原有项目编辑状态").assertExists()
        rule.onNodeWithTag("data-transfer-busy-overlay").assertDoesNotExist()
        rule.runOnUiThread {
            transfer.value = DataTransferState(busy = true, result = "正在导出本机工作数据…")
        }
        rule.waitForIdle()
        rule.onNodeWithTag("data-transfer-busy-overlay").assertExists()
        rule.onNodeWithText("正在导出本机工作数据…").assertExists()
        rule.runOnUiThread {
            transfer.value = DataTransferState(busy = false, result = "导出完成")
        }
        rule.waitForIdle()
        rule.onNodeWithTag("data-transfer-busy-overlay").assertDoesNotExist()
        rule.onNodeWithText("原有项目编辑状态").assertExists()
    }

    @Test
    fun blockedConfirmationShowsReasonAndCannotAccept() {
        var confirmed = false
        rule.setContent {
            CodexTheme {
                CodexConfirmDialog(
                    title = "确认导入本机备份",
                    message = "仍有会话任务、终端命令或审批待处理，请完成后再操作",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                    confirmLabel = "核对并导入",
                    destructive = true,
                    confirmEnabled = false
                )
            }
        }
        rule.onNodeWithText("仍有会话任务、终端命令或审批待处理，请完成后再操作")
            .assertExists()
        rule.onNodeWithTag("confirm-dialog-accept").assertIsNotEnabled()
        rule.runOnIdle { org.junit.Assert.assertFalse(confirmed) }
    }

    @Test
    fun confirmationDisablesImmediatelyWhenBackgroundTaskStarts() {
        val allowed = mutableStateOf(true)
        var accepted = false
        rule.setContent {
            CodexTheme {
                CodexConfirmDialog(
                    title = "核对并导入",
                    message = if (allowed.value) "准备导入" else
                        "仍有会话任务、终端命令或审批待处理，请完成后再操作",
                    confirmLabel = "核对并导入",
                    confirmEnabled = allowed.value,
                    onConfirm = { accepted = true },
                    onDismiss = {}
                )
            }
        }
        rule.onNodeWithTag("confirm-dialog-accept").assertIsEnabled()
        rule.runOnUiThread { allowed.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag("confirm-dialog-accept").assertIsNotEnabled()
        rule.onNodeWithText("仍有会话任务、终端命令或审批待处理，请完成后再操作")
            .assertExists()
        rule.runOnIdle { org.junit.Assert.assertFalse(accepted) }
    }

    @Test
    fun evidenceSheetShowsReadableResultAndCloses() {
        rule.setContent {
            CodexTheme {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    CodexEvidenceSheet(
                        title = "命令执行 · 执行依据",
                        payload = "{\"exitCode\":0,\"stdout\":\"done\"}",
                        onDismiss = { visible = false }
                    )
                }
            }
        }
        rule.onNodeWithText("命令执行 · 执行依据").assertExists()
        rule.waitForIdle()
        captureVerifiedDisplayScreenshot("evidence-sheet-android.png")
        rule.onNodeWithText("执行成功").assertExists()
        rule.onNodeWithText("done").assertExists()
        rule.onNodeWithText("exitCode", substring = true).assertDoesNotExist()
        rule.onNodeWithText("复制执行详情").performClick()
        rule.onNodeWithText("关闭").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("命令执行 · 执行依据").assertDoesNotExist()
    }
}
