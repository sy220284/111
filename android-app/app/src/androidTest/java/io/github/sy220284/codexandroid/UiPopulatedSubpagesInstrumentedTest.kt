package io.github.sy220284.codexandroid

import android.app.Application
import java.io.File
import java.util.UUID
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun mcpConnectionCardKeepsAgentToolsOffTheUserInterface() {
        val server = JSONObject()
            .put("name", "演示扩展服务")
            .put("runtimeStatus", "ready")
            .put("authStatus", "unauthorized")
            .put("tools", JSONObject().put("internal_database_tool", JSONObject()))
            .put("resources", JSONArray().put(JSONObject().put("uri", "mcp://secret-resource")))
        var requested: String? = null
        rule.setContent {
            CodexTheme {
                McpServiceStatusCard(server, onAuthorize = { requested = it })
            }
        }
        rule.onNodeWithText("演示扩展服务").assertExists()
        rule.onNodeWithText("已连接").assertExists()
        rule.onNodeWithText("internal_database_tool").assertDoesNotExist()
        rule.onNodeWithText("mcp://secret-resource").assertDoesNotExist()
        rule.onNodeWithText("授权登录").performClick()
        rule.runOnIdle { assertEquals("演示扩展服务", requested) }
        capture("audit-mcp-service-boundary-android35.png")
    }

    @Test fun mcpAuthorizedServiceDoesNotShowRedundantLogin() {
        val server = JSONObject().put("name", "已授权服务")
            .put("runtimeStatus", "running").put("authStatus", "authorized")
            .put("tools", JSONObject().put("agent_only_tool", JSONObject()))
        rule.setContent { CodexTheme { McpServiceStatusCard(server, onAuthorize = {}) } }
        rule.onNodeWithText("授权：已授权").assertExists()
        rule.onNodeWithText("授权登录").assertDoesNotExist()
        rule.onNodeWithText("agent_only_tool").assertDoesNotExist()
    }

    @Test fun imageDraftSurvivesActualAndroidPreferenceRestoreAndPruning() {
        // Device-protected private storage isolates this test from active user drafts.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
            .createDeviceProtectedStorageContext()
        val preferences = context.getSharedPreferences("codex-unsent-drafts", 0)
        val original = preferences.getString("drafts", null)
        val folder = LocalImageAttachments.directory(context).apply {
            assertTrue(isDirectory || mkdirs())
        }
        val photo = File(folder, "${UUID.randomUUID()}.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        }
        try {
            val key = "integration-unsent-image"
            DraftPersistence.save(context, mapOf(
                key to ComposerDraft("跨重启未发送文字", listOf(photo.canonicalPath))
            ))
            val restored = DraftPersistence.restore(context)
            assertEquals(0, restored.missingImages)
            assertEquals("跨重启未发送文字", restored.drafts[key]?.text)
            assertEquals(listOf(photo.canonicalPath), restored.drafts[key]?.images)
            val future = System.currentTimeMillis() +
                3L * AttachmentRetentionPolicy.ORPHAN_TTL_MILLIS
            assertEquals(0, AttachmentRetentionPolicy.prune(
                folder, restored.drafts.values.flatMap { it.images }.toSet(), future))
            assertTrue("Referenced unsent photo survives expiry cleanup", photo.isFile)
            assertEquals(1, AttachmentRetentionPolicy.prune(folder, emptySet(), future))
            assertTrue("Unreferenced file should be reclaimed", !photo.exists())
        } finally {
            if (original == null) assertTrue(preferences.edit().remove("drafts").commit())
            else assertTrue(preferences.edit().putString("drafts", original).commit())
            if (photo.exists()) assertTrue(photo.delete())
        }
    }

    @Test fun rtlAndDoubleFontScaleKeepBulkActionsReadableAndReachable() {
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(1f, fontScale = 2f)
            ) {
                CodexTheme {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.width(360.dp).fillMaxHeight()
                            .verticalScroll(rememberScrollState())) {
                            ThreadBulkToolbar(
                                selectedCount = 3, archived = false, enabled = true,
                                onSelectAll = {}, onClear = {}, onAction = {}
                            )
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("thread-bulk-archive").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("thread-bulk-delete").performScrollTo().assertIsDisplayed()
        val archive = rule.onNodeWithTag("thread-bulk-archive").fetchSemanticsNode().boundsInRoot
        val delete = rule.onNodeWithTag("thread-bulk-delete").fetchSemanticsNode().boundsInRoot
        assertTrue("Large-font actions must stack vertically", delete.top > archive.top)
        assertTrue("Touch targets remain at least 48dp tall", archive.height >= 48f && delete.height >= 48f)
        capture("audit-rtl-200font-bulk-android35.png")
    }

    @Test fun disabledBatchActionsExplainTheirStateToScreenReaders() {
        rule.setContent {
            CodexTheme {
                ThreadBulkToolbar(
                    selectedCount = 0, archived = false, enabled = true,
                    onSelectAll = {}, onClear = {}, onAction = {}
                )
            }
        }
        rule.onNodeWithTag("thread-bulk-delete")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "请先选择需要删除的会话"))
        rule.onNodeWithTag("thread-bulk-archive")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "请先选择会话"))
    }

    @Test fun sidebarUsesRealThreadListAndFiltersWithoutLosingCurrentSelection() {
        val now = java.time.Instant.now().epochSecond
        val response = JSONObject().put("threads", JSONArray()
            .put(JSONObject().put("id", "ci-selected")
                .put("name", "修复工作区登录").put("cwd", "/projects/a")
                .put("updatedAt", now))
            .put(JSONObject().put("id", "ci-build")
                .put("name", "检查构建流水线").put("cwd", "/projects/b")
                .put("updatedAt", now - 3 * 86400)))
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
        rule.onNodeWithTag("sidebar-project-/projects/a").assertExists()
        rule.onNodeWithTag("sidebar-project-/projects/b").assertExists()
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

    @Test fun approvalLogShowsPrivateHistoryAndConfirmsDeletion() {
        var cleared = false
        rule.setContent {
            CodexTheme {
                ApprovalAuditSection(
                    listOf(ApprovalAuditRecord(1730000000000, "执行命令", "允许本次", "git status")),
                    null, onClear = { cleared = true }
                )
            }
        }
        rule.onNodeWithTag("approval-audit-toggle").performClick()
        rule.onNodeWithText("git status").assertIsDisplayed()
        capture("audit-approval-history-android35.png")
        rule.onNodeWithTag("approval-audit-clear").performClick()
        rule.onNodeWithText("确认清除").performClick()
        rule.runOnIdle { assertEquals(true, cleared) }
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
                    followUpMode = "queue", onFollowUpModeChange = {},
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

    @Test fun sidebarShowsOtherThreadApprovalAndQuestionWithoutChangingActiveThread() {
        val entries = listOf(
            JSONObject().put("id", "local").put("name", "当前会话")
                .put("cwd", "/projects/local"),
            JSONObject().put("id", "remote-approval").put("name", "远程审批")
                .put("cwd", "/projects/remote"),
            JSONObject().put("id", "remote-input").put("name", "远程问题")
                .put("cwd", "/projects/remote")
        )
        rule.setContent {
            CodexTheme {
                Column {
                    SidebarSessionsContent(
                        all = entries, hasResponse = true, connected = true,
                        loading = false, hasError = false, activeId = "local",
                        running = false, switching = false, onRefresh = {}, onOpenThread = {},
                        liveActivities = mapOf(
                            "remote-approval" to ThreadActivity.WaitingApproval,
                            "remote-input" to ThreadActivity.WaitingAnswer
                        )
                    )
                }
            }
        }
        rule.onNodeWithText("2 条会话正在执行或等待回应").assertExists()
        rule.onNodeWithText("等待审批").assertExists()
        rule.onNodeWithText("等待回答").assertExists()
        rule.onNodeWithContentDescription("当前会话").assertExists()
        capture("audit-sidebar-background-status-android35.png")
    }

    @Test fun sidebarTaskCenterSwitchesAndStopsOnlyTheSelectedThread() {
        val entries = listOf(
            JSONObject().put("id", "first").put("name", "任务甲"),
            JSONObject().put("id", "second").put("name", "任务乙")
        )
        var opened = ""
        var stopped = ""
        rule.setContent {
            CodexTheme {
                SidebarSessionsContent(
                    all = entries, hasResponse = true, connected = true,
                    loading = false, hasError = false, activeId = "first",
                    running = true, switching = false, onRefresh = {},
                    onOpenThread = { opened = it }, onStopThread = { stopped = it },
                    liveExecutions = mapOf(
                        "first" to ThreadExecution(turnId = "turn-one"),
                        "second" to ThreadExecution(turnId = "turn-two")
                    )
                )
            }
        }
        rule.onNodeWithText("2 条会话正在执行或等待回应").assertExists()
        rule.onNodeWithTag("sidebar-task-open-second").performClick()
        rule.runOnIdle { assertEquals("second", opened) }
        rule.onNodeWithTag("sidebar-task-stop-first").performClick()
        rule.runOnIdle { assertEquals("first", stopped) }
        rule.onNodeWithTag("sidebar-thread-second").assertExists()
        capture("audit-multi-thread-tasks-android35.png")
    }

    @Test fun questionApprovalRequiresAnswersAndHasNoInvalidDeclineButton() {
        val vm = viewModel()
        val request = ServerPrompt("ci-user-input", "item/tool/requestUserInput",
            JSONObject().put("questions", JSONArray()
                .put(JSONObject().put("id", "scope").put("header", "执行范围")
                    .put("question", "请确定要操作的工作区"))))
        rule.setContent { CodexTheme { ApprovalRequestCard(vm, request) } }
        rule.onNodeWithText("拒绝").assertDoesNotExist()
        rule.onNodeWithText("提交全部回答").assertIsNotEnabled()
        rule.onNodeWithText("等待回答时可返回对话查看任务；停止任务可取消本次提问。")
            .assertExists()
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
            var mode by remember { mutableStateOf("queue") }
            CodexTheme {
                ConversationComposer(
                    draft = "检查这一轮修改", onDraftChange = {},
                    attachedCount = 0, connected = true,
                    running = true, stopping = false,
                    canStop = true, canSend = true, sending = false,
                    followUpMode = mode, onFollowUpModeChange = { mode = it },
                    onAttach = {}, onStop = { stopped = true }, onSend = { sent = true }
                )
            }
        }
        rule.onNodeWithTag("composer-stop").assertIsDisplayed()
        rule.onNodeWithTag("composer-send").assertIsDisplayed()
        rule.onNodeWithTag("composer-mode-queue").assertIsDisplayed()
        rule.onNodeWithTag("composer-mode-steer").assertIsDisplayed()
        rule.onNodeWithContentDescription("加入后续执行队列").assertExists()
        rule.onNodeWithTag("composer-mode-steer").performClick()
        rule.onNodeWithContentDescription("立即调整当前任务").assertExists()
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
        rule.onNodeWithText("待处理审批").assertExists()
        rule.onNodeWithText("命令执行确认").assertExists()
        rule.onNodeWithText("git status").assertExists()
        rule.onNodeWithText("仅本次允许").assertExists()
        capture("audit-conversation-approval-android35.png")
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
        rule.onNodeWithTag("elicitation-submit").assertExists().assertIsNotEnabled()
        rule.onNodeWithTag("elicitation-field-title").performTextInput("验收标题")
        rule.onNodeWithTag("elicitation-submit").assertIsEnabled()
        rule.onNodeWithTag("elicitation-field-title").performTextClearance()
        rule.onNodeWithTag("elicitation-submit").assertIsNotEnabled()
        rule.onNodeWithTag("elicitation-field-title").performTextInput("验收标题")
        rule.onNodeWithTag("elicitation-submit").assertIsEnabled().performClick()
        rule.onNodeWithText("向 MCP 服务提交确认").assertExists()
        rule.onNodeWithTag("confirm-dialog-cancel").assertExists().performClick()
        rule.onNodeWithText("向 MCP 服务提交确认").assertDoesNotExist()
        capture("audit-mcp-elicitation-android35.png")
    }

    @Test fun requiredBooleanMcpChoiceMustBeExplicitAndCanBeDeclined() {
        val vm = viewModel()
        val props = JSONObject().put("permission", JSONObject()
            .put("type", "boolean").put("title", "允许读取公开数据"))
        val params = JSONObject().put("serverName", "CI 工具服务")
            .put("type", "form").put("message", "请选择授权")
            .put("requestedSchema", JSONObject().put("type", "object")
                .put("properties", props).put("required", JSONArray().put("permission")))
        val prompt = ServerPrompt("ci-boolean", "mcpServer/elicitation/request", params)
        rule.setContent {
            CodexTheme { Column { McpElicitationRequestCard(vm, prompt) } }
        }
        rule.onNodeWithTag("elicitation-submit").assertIsNotEnabled()
        rule.onNodeWithTag("elicitation-deny-permission").performClick()
        rule.onNodeWithTag("elicitation-submit").assertIsEnabled()
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
