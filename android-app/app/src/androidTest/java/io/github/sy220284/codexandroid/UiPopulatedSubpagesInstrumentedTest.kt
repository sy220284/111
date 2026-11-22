package io.github.sy220284.codexandroid

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 独立真实 Compose 屏幕测试：使用明确标记的协议形状样例验证填充态，
 * 从而无需伪装成本机引擎已经连接。不会发起危险审批或工具写入。
 */
class UiPopulatedSubpagesInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    private fun viewModel(): CodexViewModel {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        return CodexViewModel(app)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        captureVerifiedDisplayScreenshot(name)
    }

    @Test fun sidebarUsesRealThreadListAndFiltersWithoutLosingCurrentSelection() {
        val response = JSONObject().put("threads", JSONArray()
            .put(JSONObject().put("id", "ci-selected")
                .put("name", "修复工作区登录").put("cwd", "/projects/a"))
            .put(JSONObject().put("id", "ci-build")
                .put("name", "检查构建流水线").put("cwd", "/projects/b")))
        // 绘制数据与内核连接拆开；真实内核启动也不会覆盖本测试的固定数据。
        val sessions = records(response)
        rule.setContent {
            CodexTheme {
                Column {
                    SidebarSessionsContent(
                        all = sessions, hasResponse = true, connected = true,
                        loading = false, hasError = false, activeId = "ci-selected",
                        running = false, switching = false, onRefresh = {}, onOpenThread = {}
                    )
                }
            }
        }
        rule.onNodeWithText("修复工作区登录").assertExists()
        rule.onNodeWithText("检查构建流水线").assertExists()
        rule.onNodeWithContentDescription("当前会话").assertExists()
        rule.onNodeWithTag("sidebar-search-toggle").performClick()
        rule.onNodeWithTag("sidebar-search").performTextInput("构建")
        rule.onNodeWithText("修复工作区登录").assertDoesNotExist()
        rule.onNodeWithText("检查构建流水线").assertExists()
        capture("audit-sidebar-filtered-android35.png")
    }

    @Test fun batchToolbarSupportsAllSelectAndActionWithRealComposeClicks() {
        var selected by mutableIntStateOf(0)
        var requested = ""
        rule.setContent {
            CodexTheme {
                ThreadBulkToolbar(
                    selectedCount = selected,
                    archived = false,
                    enabled = true,
                    onSelectAll = { selected = 2 },
                    onClear = { selected = 0 },
                    onAction = { requested = it }
                )
            }
        }
        rule.onNodeWithTag("thread-bulk-delete").assertIsNotEnabled()
        rule.onNodeWithText("全选本页").performClick()
        rule.onNodeWithText("已选 2 条 · 仅针对当前筛选页").assertExists()
        rule.onNodeWithTag("thread-bulk-archive").assertIsEnabled()
        rule.onNodeWithTag("thread-bulk-delete").performClick()
        rule.runOnIdle { assertEquals("delete", requested) }
        capture("audit-thread-bulk-selection-android35.png")
    }

    @Test fun composerTextEditingUpdatesSendAvailability() {
        var text by mutableStateOf("")
        var submitted = ""
        rule.setContent {
            CodexTheme {
                ConversationComposer(
                    draft = text, onDraftChange = { text = it },
                    attachedCount = 0, connected = true, running = false,
                    stopping = false, canStop = false,
                    canSend = text.isNotBlank(), sending = false,
                    onAttach = {}, onStop = {}, onSend = { submitted = text }
                )
            }
        }
        rule.onNodeWithTag("composer-send").assertIsNotEnabled()
        rule.onNodeWithTag("composer-input").performTextInput("检查交互链路")
        rule.onNodeWithTag("composer-send").assertIsEnabled()
        capture("audit-composer-edit-android35.png")
        rule.onNodeWithTag("composer-send").performClick()
        rule.runOnIdle { assertEquals("检查交互链路", submitted) }
    }

    @Test fun sidebarSessionRendersRealThreadAndOpensSelectedId() {
        val item = JSONObject().put("id", "ci-thread-001")
            .put("name", "检查工作区代码")
            .put("cwd", "/data/user/0/ci/projects")
        var opened = ""
        rule.setContent {
            CodexTheme {
                SidebarSessionRow(
                    item = item, active = true, running = false, enabled = true,
                    onClick = { opened = item.optString("id") }
                )
            }
        }
        rule.onNodeWithTag("sidebar-thread-ci-thread-001").assertIsDisplayed()
        rule.onNodeWithText("检查工作区代码").assertExists()
        rule.onNodeWithContentDescription("当前会话").assertExists()
        capture("audit-sidebar-thread-android35.png")
        rule.onNodeWithTag("sidebar-thread-ci-thread-001").performClick()
        rule.runOnIdle { assertEquals("ci-thread-001", opened) }
    }

    @Test fun composerKeepsStopAndSteerReachableDuringActiveTurn() {
        var sent = false
        var stopped = false
        rule.setContent {
            CodexTheme {
                ConversationComposer(
                    draft = "检查这一轮修改", onDraftChange = {},
                    attachedCount = 0, connected = true,
                    running = true, stopping = false,
                    canStop = true, canSend = true, sending = false,
                    onAttach = {}, onStop = { stopped = true }, onSend = { sent = true }
                )
            }
        }
        rule.onNodeWithTag("composer-stop").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithContentDescription("补充任务指令").assertExists()
        capture("audit-composer-running-android35.png")
        rule.onNodeWithTag("composer-stop").performClick()
        rule.onNodeWithTag("composer-send").performClick()
        rule.runOnIdle {
            assertEquals(true, stopped)
            assertEquals(true, sent)
        }
    }

    @Test fun conversationApprovalSheetRendersActualCommandAndDecision() {
        val vm = viewModel()
        val request = ServerPrompt("ci-approval", "item/commandExecution/requestApproval",
            JSONObject().put("command", "git status")
                .put("reason", "确认本机工作区当前改动")
                .put("cwd", "/data/user/0/ci/projects"))
        rule.setContent {
            CodexTheme {
                ApprovalQuickSheetContent(vm, listOf(request), onDismiss = {})
            }
        }
        rule.onNodeWithText("处理当前任务的审批").assertExists()
        rule.onNodeWithText("命令执行确认").assertExists()
        rule.onNodeWithText("git status").assertExists()
        rule.onNodeWithText("仅本次允许").assertExists()
        capture("audit-conversation-approval-android35.png")
    }

    @Test fun mcpToolInputRendersActualSchemaFields() {
        val vm = viewModel()
        vm.activeThread.value = "ci-fixture-session"
        val schema = JSONObject().put("type", "object")
            .put("required", JSONArray().put("project"))
            .put("properties", JSONObject()
                .put("project", JSONObject().put("type", "string")
                    .put("title", "项目名称").put("description", "要检查的项目"))
                .put("dryRun", JSONObject().put("type", "boolean")
                    .put("title", "仅检查")))
        val tool = JSONObject().put("title", "检查项目")
            .put("description", "读取项目当前状态")
            .put("inputSchema", schema)
        rule.setContent {
            CodexTheme {
                McpToolCallSheet(vm, "CI 工具服务", "inspect", tool, {}, {})
            }
        }
        rule.onNodeWithText("核对并调用工具").assertExists()
        rule.onNodeWithText("仅检查").assertExists()
        capture("audit-mcp-tool-form-android35.png")
    }

    @Test fun mcpResourceRendersReturnedDocumentText() {
        val vm = viewModel()
        val resource = JSONObject().put("name", "帮助文档")
            .put("uri", "docs://help")
            .put("description", "服务使用说明")
        rule.setContent {
            CodexTheme {
                McpResourceSheet("CI 工具服务", resource, vm, {})
            }
        }
        rule.waitForIdle()
        vm.data.value = vm.data.value + ("mcpResource:CI 工具服务:docs://help" to
            JSONObject().put("contents", JSONArray().put(JSONObject()
                .put("mimeType", "text/plain")
                .put("text", "阅读工具使用说明与权限要求。"))))
        rule.onNodeWithText("资源片段 1").assertExists()
        capture("audit-mcp-resource-android35.png")
    }

    @Test fun elicitationRendersOnlyRequiredUserForm() {
        val vm = viewModel()
        val props = JSONObject().put("title", JSONObject().put("type", "string")
            .put("title", "文档标题"))
        val params = JSONObject().put("serverName", "CI 工具服务")
            .put("type", "form").put("message", "请填写文档标题")
            .put("requestedSchema", JSONObject().put("type", "object")
                .put("properties", props)
                .put("required", JSONArray().put("title")))
        val prompt = ServerPrompt("ci-form", "mcpServer/elicitation/request", params)
        rule.setContent {
            CodexTheme {
                Column { McpElicitationRequestCard(vm, prompt) }
            }
        }
        rule.onNodeWithText("MCP 服务请求用户操作").assertExists()
        rule.onNodeWithText("核对并提交").assertExists()
        capture("audit-mcp-elicitation-android35.png")
    }

    @Test fun pluginDetailShowsCapabilitiesRatherThanRawMetadata() {
        val vm = viewModel()
        val market = JSONObject().put("name", "样例插件源").put("path", "/plugins")
        val entry = JSONObject().put("name", "fixture").put("id", "fixture")
            .put("installed", true)
            .put("interface", JSONObject().put("displayName", "项目检查")
                .put("shortDescription", "检查任务结果"))
        rule.setContent {
            CodexTheme {
                PluginDetailSheet(market, entry, vm, {})
            }
        }
        rule.waitForIdle()
        val summary = JSONObject().put("installed", true).put("version", "1.0.0")
        val plugin = JSONObject().put("summary", summary)
            .put("description", "检查任务结果")
            .put("skills", JSONArray().put(JSONObject().put("name", "project-check")))
        vm.data.value = vm.data.value + ("pluginDetail:样例插件源:fixture" to
            JSONObject().put("plugin", plugin))
        rule.onNodeWithText("提供的技能").assertExists()
        capture("audit-plugin-detail-android35.png")
    }
}
