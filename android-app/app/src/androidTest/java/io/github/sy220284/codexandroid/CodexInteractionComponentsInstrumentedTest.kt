package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 实际模拟器验证：详情不挤动列表、可关闭，上下文菜单能完成所选动作。 */
class CodexInteractionComponentsInstrumentedTest {
    @Test fun singleQuestionKeyboardDoneAppliesOnlyAValidReply() {
        var applied = "原值"
        rule.setContent {
            CodexTheme {
                ActionQuestionRow(applied, { applied = it }, "名称", replyTag = "keyboard-reply",
                    validate = { if (it.isBlank()) "需要名称" else null })
            }
        }
        rule.onNodeWithText("修改").performClick()
        rule.onNodeWithTag("keyboard-reply-input").performTextReplacement("")
        rule.onNodeWithTag("keyboard-reply-input").performImeAction()
        rule.runOnIdle { assertEquals("原值", applied) }
        rule.onNodeWithTag("keyboard-reply-input").assertExists().performTextReplacement("新名称")
        rule.onNodeWithTag("keyboard-reply-input").performImeAction()
        rule.onNodeWithTag("keyboard-reply-input").assertDoesNotExist()
        rule.runOnIdle { assertEquals("新名称", applied) }
    }

    @Test fun unreadableMediaRecordOffersExplicitClearWithoutAnotherProviderCall() {
        var cleared = false
        var providerCalls = 0
        var selectedRecords = 0
        rule.setContent {
            CodexTheme {
                MediaAnalysisActions(emptyList(), MediaAnalysisController.State(owner = "thread",
                    scope = "a".repeat(64), error = "分析记录无法读取，原记录已保留"),
                    owner = "thread", enabled = false, onChoose = { _, _ -> providerCalls++ },
                    onContinue = { _, _ -> providerCalls++ }, onSelectRecord = { selectedRecords++ },
                    onCancel = {}, onReview = {}, onDismiss = { cleared = true })
            }
        }
        rule.onNodeWithText("分析记录无法读取，原记录已保留").assertIsDisplayed()
        rule.onNodeWithText("清除损坏记录").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(true, cleared); assertEquals(0, providerCalls); assertEquals(0, selectedRecords) }
    }

    @get:Rule val rule = createComposeRule()

    @Test fun markdownFileLinksUseTheSameNativeFileOpenerAsCards() {
        var opened: String? = null
        rule.setContent {
            CodexTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalReplyFileOpen provides { opened = it }) {
                    NativeMessageMarkdown("[下载报告](sandbox:/workspace/report.pdf)")
                }
            }
        }
        rule.onNodeWithText("下载报告", substring = true).performTouchInput { click(center) }
        rule.runOnIdle { org.junit.Assert.assertEquals("/workspace/report.pdf", opened) }
    }

    @Test fun isolatedMcpAppRealWebViewRoundTrip() {
        val port = java.util.concurrent.atomic.AtomicReference<android.webkit.WebMessagePort>()
        val view = java.util.concurrent.atomic.AtomicReference<android.webkit.WebView>()
        val receipt = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val html = """
            <!doctype html><script>
            (async function(){
              let parentDenied=false, storageDenied=false, networkDenied=false;
              try { void parent.document.body; } catch(e) { parentDenied=true; }
              try { localStorage.setItem("probe","blocked"); } catch(e) { storageDenied=true; }
              try { await fetch("https://111-mcp-test.invalid/probe"); } catch(e) { networkDenied=true; }
              addEventListener("message", e=>{
                if(e.source===parent && e.data.result && e.data.result.receipt==="native")
                  parent.postMessage({jsonrpc:"2.0",method:"test/ack"},"*");
              });
              parent.postMessage({jsonrpc:"2.0",id:1,method:"test/isolation",params:{parentDenied,storageDenied,networkDenied}},"*");
            })();
            </script>
        """.trimIndent()
        try {
            rule.setContent {
                androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                    android.webkit.WebView(context).apply {
                        view.set(this)
                        McpAppSandbox.attach(this, html, { true }, { port.set(it) }, { raw ->
                            val msg = org.json.JSONObject(raw)
                            when (msg.optString("method")) {
                                "test/isolation" -> {
                                    receipt.set(msg.getJSONObject("params"))
                                    port.get().postMessage(android.webkit.WebMessage(
                                        """{"jsonrpc":"2.0","id":1,"result":{"receipt":"native"}}"""))
                                }
                                "test/ack" -> finished.set(true)
                            }
                        })
                    }
                })
            }
            rule.waitUntil(20000L) { finished.get() }
            val isolated = requireNotNull(receipt.get())
            org.junit.Assert.assertTrue(isolated.getBoolean("parentDenied"))
            org.junit.Assert.assertTrue(isolated.getBoolean("storageDenied"))
            org.junit.Assert.assertTrue(isolated.getBoolean("networkDenied"))
        } finally {
            rule.runOnIdle { port.get()?.close(); view.get()?.stopLoading(); view.get()?.destroy() }
        }
    }


    @Test fun adaptiveActionsKeepLabelsAndCallbacksAtDoubleFontScale() {
        val chosen = mutableListOf<String>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                CodexTheme {
                    Box(Modifier.width(280.dp)) {
                        CodexAdaptiveActions(
                            first = { modifier ->
                                CodexSecondaryButton(onClick = { chosen.add("first") },
                                    modifier = modifier.testTag("adaptive-action-first")) { Text("保存并继续") }
                            },
                            second = { modifier ->
                                CodexSecondaryButton(onClick = { chosen.add("second") },
                                    modifier = modifier.testTag("adaptive-action-second")) { Text("复制执行详情") }
                            }
                        )
                    }
                }
            }
        }
        val first = rule.onNodeWithTag("adaptive-action-first").assertIsDisplayed()
        val second = rule.onNodeWithTag("adaptive-action-second").assertIsDisplayed()
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val secondBounds = second.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(secondBounds.top >= firstBounds.bottom)
        first.performClick()
        second.performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(listOf("first", "second"), chosen) }
    }

    @Test fun asynchronousUiStateCancelsOldOwnerAndPublishesOnMain() {
        val request = mutableStateOf("old")
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val oldRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val newRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        rule.setContent {
            val owner = request.value
            val result by produceCodexUiState("loading-" + owner, owner) {
                try {
                    val output = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        if (owner == "old") {
                            started.complete(Unit)
                            oldRelease.await()
                        } else newRelease.await()
                        "result-" + owner
                    }
                    check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    value = output
                } finally {
                    if (owner == "old") cancelled.complete(Unit)
                }
            }
            Text(result, Modifier.testTag("shared-async-result"))
        }
        rule.waitUntil(5000L) { started.isCompleted }
        rule.onNodeWithTag("shared-async-result").assertTextEquals("loading-old")
        rule.runOnIdle { request.value = "new" }
        rule.waitUntil(5000L) { cancelled.isCompleted }
        rule.onNodeWithTag("shared-async-result").assertTextEquals("loading-new")
        oldRelease.complete(Unit)
        newRelease.complete(Unit)
        rule.waitUntil(5000L) {
            rule.onAllNodesWithText("result-new").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("shared-async-result").assertTextEquals("result-new")
    }

    @Test fun inlineModelSliderCommitsOneActualChoiceAndSupportsReset() {
        val setting = EngineSettings.byKey("model_verbosity")!!
        val writes = mutableListOf<Any>()
        rule.setContent {
            var current by remember { mutableStateOf<Any?>(null) }
            CodexTheme {
                EngineModelSlider(setting, current, setting.choices, loaded = true, enabled = true) { value ->
                    writes.add(value)
                    current = if (value === org.json.JSONObject.NULL) null else value
                }
            }
        }
        val slider = rule.onNodeWithTag("engine-slider-model_verbosity")
        rule.onNodeWithTag("engine-slider-value-model_verbosity").assertTextEquals("由引擎决定")
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
        rule.onNodeWithTag("engine-slider-value-model_verbosity").assertTextEquals("详细")
        rule.runOnIdle { org.junit.Assert.assertEquals(listOf<Any>("high"), writes) }
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        rule.onNodeWithTag("engine-slider-value-model_verbosity").assertTextEquals("由引擎决定")
        rule.runOnIdle {
            org.junit.Assert.assertEquals(2, writes.size)
            org.junit.Assert.assertSame(org.json.JSONObject.NULL, writes.last())
        }
        rule.onNodeWithTag("codex-detail-sheet").assertDoesNotExist()
    }

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
                            CodexSheetAction("复制", CodexGlyph.ContentCopy,
                                onClick = { result = "复制已执行" }),
                            CodexSheetAction("不可用操作", CodexGlyph.ContentCopy,
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
            CodexTheme { Column {
                ClipboardQueryAction("明确检索", query, { query = it }, { query = "" }, tag = "clipboard-test")
            } }
        }
        setQueryClipboard("新任务")
        rule.onNodeWithTag("clipboard-test").performClick()
        rule.onNodeWithText("新任务").assertExists()
        rule.onNodeWithText("清除查找条件").performClick()
        rule.onNodeWithText("新任务").assertDoesNotExist()
    }

    private fun setQueryClipboard(value: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("测试检索词", value))
        }
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
        rule.onNodeWithText("遇到阻碍").assertIsSelected()
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
                        icon = CodexGlyph.Settings,
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
                                icon = CodexGlyph.Settings,
                                onClick = { performed = "已进入重命名" }
                            ),
                            CodexSheetAction(
                                label = "不可移动",
                                icon = CodexGlyph.Settings,
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

    @Test fun budgetEditDialogCommitsOnlyAfterConfirmation() {
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            var input by remember { mutableStateOf("") }
            var budget by remember { mutableStateOf("原有额度：9000") }
            CodexTheme {
                Column {
                    Text(budget)
                    TextButton(onClick = { input = ""; open = true }) { Text("修改额度") }
                    if (open) CodexEditDialog(
                        title = "执行额度",
                        description = "留空表示清除上限",
                        confirmLabel = "应用额度",
                        onDismiss = { open = false },
                        onConfirm = { budget = "待保存：" + input; open = false },
                        canConfirm = input.isBlank() ||
                            (input.toLongOrNull()?.let { it > 0 } == true)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Token 上限") },
                            modifier = androidx.compose.ui.Modifier.testTag("test-budget-input")
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("修改额度").performClick()
        rule.onNodeWithTag("codex-edit-dialog").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("原有额度：9000").assertExists()
        rule.onNodeWithText("修改额度").performClick()
        rule.onNodeWithTag("test-budget-input").performTextInput("0")
        rule.onNodeWithTag("codex-edit-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("test-budget-input").performTextClearance()
        rule.onNodeWithTag("test-budget-input").performTextInput("5000")
        rule.onNodeWithTag("codex-edit-confirm").assertIsEnabled().performClick()
        rule.onNodeWithText("待保存：5000").assertExists()
        rule.onNodeWithTag("codex-edit-dialog").assertDoesNotExist()
    }

    @Test fun longDetailContentScrollsWhileCloseButtonRemainsAvailable() {
        rule.setContent {
            CodexTheme {
                CodexDetailSheet(title = "完整诊断", onDismiss = {}) {
                    repeat(30) { i -> Text("诊断条目 " + i) }
                }
            }
        }
        rule.onNodeWithText("诊断条目 29").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("codex-detail-close").assertIsDisplayed()
    }

    @Test fun doubleFontSizeKeepsStatusChoicesAndEditorActionsReachable() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                CodexTheme {
                    Box(androidx.compose.ui.Modifier.width(360.dp)) {
                        Column {
                            AtlasStatusSelector(
                                selected = "blocked",
                                options = listOf(
                                    AtlasStatusOption("active", "进行中", StatusTone.Info),
                                    AtlasStatusOption("paused", "已暂停", StatusTone.Neutral),
                                    AtlasStatusOption("blocked", "遇到阻碍", StatusTone.Warning),
                                    AtlasStatusOption("complete", "已完成", StatusTone.Success)
                                ),
                                onSelect = {}
                            )
                            CodexEditDialog(
                                title = "修改执行额度",
                                description = "只在确认后改动草稿",
                                confirmLabel = "应用额度",
                                onDismiss = {},
                                onConfirm = {},
                                canConfirm = true
                            ) {
                                Text("Token 上限，输入数字后应用")
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("遇到阻碍").assertIsSelected()
        rule.onNodeWithTag("codex-edit-confirm").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test fun searchableModelChoicesKeepSelectionAndClearFilter() {
        rule.setContent {
            var selected by remember { mutableStateOf("alpha") }
            CodexTheme {
                CodexChoiceSheet(title = "选择模型", description = "选择实际可用模型",
                    choices = listOf(CodexChoice("alpha", "Alpha"), CodexChoice("beta", "Beta")),
                    selectedId = selected, onSelect = { selected = it }, onDismiss = {},
                    wide = true)
            }
        }
        rule.onNodeWithTag("choice-option-alpha").assertIsSelected()
        rule.onNodeWithTag("choice-search").assertDoesNotExist()
        rule.onNodeWithTag("choice-option-alpha").assertExists()
        rule.onNodeWithTag("choice-option-beta").performClick()
        rule.onNodeWithTag("choice-option-beta").assertIsSelected()
        rule.onNodeWithTag("choice-option-alpha").assertIsNotSelected()
        rule.onNodeWithTag("choice-option-beta").assertIsSelected()
    }

    @Test fun compactComposerChoiceUsesExclusiveSelectionAndPreservesCallbacks() {
        rule.setContent {
            var selected by remember { mutableStateOf("queue") }
            CodexTheme {
                AtlasCompactChoice(selected,
                    listOf("queue" to "完成后执行", "steer" to "立即纠偏"),
                    { selected = it }, tagPrefix = "choice-")
            }
        }
        rule.onNodeWithTag("choice-queue").assertIsSelected()
        rule.onNodeWithTag("choice-steer").performClick().assertIsSelected()
        rule.onNodeWithTag("choice-queue").assertIsNotSelected()
    }

    @Test fun fullTextSearchShowsFirstAndPreviousMatchesWithoutChangingOriginalText() {
        rule.setContent {
            CodexTheme { FullTextReaderDialog("阅读记录", "alpha beta alpha", onDismiss = {}) }
        }
        setQueryClipboard("alpha")
        rule.onNodeWithTag("full-text-clipboard-query").performClick()
        rule.onNodeWithText("第 1 / 2 处").assertExists()
        rule.onNodeWithContentDescription("上一个匹配").performClick()
        rule.onNodeWithText("第 2 / 2 处").assertExists()
        captureComponentEvidence("audit-full-text-match-android.png")
        rule.onNodeWithContentDescription("下一个匹配").performClick()
        rule.onNodeWithText("第 1 / 2 处").assertExists()
        rule.onNodeWithText("清除查找条件").performClick()
        rule.onNodeWithText("alpha beta alpha").assertExists()
    }

    @Test fun everyGlyphAndStateArtworkRendersThroughSharedComponents() {
        val artwork = listOf(R.drawable.ic_empty_thread, R.drawable.ic_empty_content,
            R.drawable.ic_empty_no_results, R.drawable.ic_empty_request_failed,
            R.drawable.ic_empty_diff, R.drawable.ic_empty_approval, R.drawable.ic_empty_connection)
        val originalDark = UiPreferences.darkMode.value
        try {
            rule.setContent {
                CodexTheme {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = androidx.compose.ui.Modifier.testTag("asset-test-list")
                    ) {
                        items((CodexGlyph.entries.size + 7) / 8) { row ->
                            androidx.compose.foundation.layout.Row {
                                CodexGlyph.entries.drop(row * 8).take(8).forEach { glyph ->
                                    CodexIcon(glyph, glyph.name,
                                        modifier = androidx.compose.ui.Modifier.testTag("glyph-" + glyph.name))
                                }
                            }
                        }
                        items(artwork.size) { index ->
                            CodexStateArtwork(artwork[index],
                                androidx.compose.ui.Modifier.testTag("artwork-" + index))
                        }
                    }
                }
            }
            for (dark in listOf(false, true)) {
                rule.runOnIdle { UiPreferences.setDarkMode(dark) }
                CodexGlyph.entries.chunked(8).forEachIndexed { row, glyphs ->
                    rule.onNodeWithTag("asset-test-list").performScrollToIndex(row)
                    glyphs.forEach { glyph ->
                        rule.onNodeWithTag("glyph-" + glyph.name).assertExists()
                    }
                }
                val rows = (CodexGlyph.entries.size + 7) / 8
                artwork.indices.forEach { index ->
                    rule.onNodeWithTag("asset-test-list").performScrollToIndex(rows + index)
                    rule.onNodeWithTag("artwork-" + index).assertExists()
                }
            }
        } finally {
            rule.runOnIdle { UiPreferences.setDarkMode(originalDark) }
        }
    }

    @Test fun validatedFormShowsFieldErrorAndClearAction() {
        rule.setContent {
            var address by remember { mutableStateOf("") }
            CodexTheme {
                Column {
                    ActionQuestionRow(address, { address = it }, "服务地址",
                        modifier = androidx.compose.ui.Modifier.testTag("test-service-address"),
                        error = if (address.isNotBlank() && !ProviderAddressPolicy.valid(address))
                            "请输入有效的 HTTP 或 HTTPS 地址" else null)
                    TextButton(onClick = {}, enabled = ProviderAddressPolicy.valid(address),
                        modifier = androidx.compose.ui.Modifier.testTag("test-save-address")) { Text("保存") }
                }
            }
        }
        rule.onNodeWithTag("test-save-address").assertIsNotEnabled()
        rule.onNodeWithTag("test-service-address").performClick()
        rule.onNodeWithTag("action-reply-input").performTextInput("not-an-address")
        rule.onNodeWithTag("action-reply-send").performClick()
        rule.onNodeWithText("请输入有效的 HTTP 或 HTTPS 地址").assertExists()
        rule.onNodeWithTag("test-save-address").assertIsNotEnabled()
        rule.onNodeWithTag("test-service-address").performClick()
        rule.onNodeWithTag("action-reply-input").performTextReplacement("https://example.com/v1")
        rule.onNodeWithTag("action-reply-send").performClick()
        rule.onNodeWithText("请输入有效的 HTTP 或 HTTPS 地址").assertDoesNotExist()
        rule.onNodeWithTag("test-save-address").assertIsEnabled()
    }

    @Test fun actionReplyCancellationPreservesOriginalValue() {
        var committed = "原始名称"
        rule.setContent {
            var current by remember { mutableStateOf(committed) }
            CodexTheme {
                Column {
                    ActionQuestionRow(current, { current = it; committed = it }, "名称",
                        modifier = androidx.compose.ui.Modifier.testTag("reply-row"))
                }
            }
        }
        rule.onNodeWithTag("action-reply-input").assertDoesNotExist()
        rule.onNodeWithTag("reply-row").performClick()
        rule.onNodeWithTag("action-reply-input").performTextReplacement("新名称")
        rule.onNodeWithTag("action-reply-cancel").performClick()
        rule.onNodeWithTag("reply-row").assertTextContains("原始名称")
        rule.runOnIdle { org.junit.Assert.assertEquals("原始名称", committed) }
        rule.onNodeWithTag("reply-row").performClick()
        rule.onNodeWithTag("action-reply-input").performTextReplacement("已确认名称")
        rule.onNodeWithTag("action-reply-send").performClick()
        rule.onNodeWithTag("reply-row").assertTextContains("已确认名称")
        rule.runOnIdle { org.junit.Assert.assertEquals("已确认名称", committed) }
    }

    @Test fun attachmentPreviewSupportsZoomSwitchResetAndRemove() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val first = java.io.File.createTempFile("preview-first", ".png", context.cacheDir)
        val second = java.io.File.createTempFile("preview-second", ".png", context.cacheDir)
        val bitmap = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
        fun writePreviewFixture(file: java.io.File, marker: Int, label: String) {
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            canvas.drawColor(android.graphics.Color.rgb(237, 242, 239))
            paint.color = android.graphics.Color.rgb(182, 207, 194)
            canvas.drawRect(0f, 270f, 640f, 480f, paint)
            paint.color = android.graphics.Color.rgb(246, 195, 95)
            canvas.drawCircle(510f, 105f, 50f, paint)
            paint.color = android.graphics.Color.rgb(44, 76, 61)
            val landscape = android.graphics.Path().apply {
                moveTo(0f, 380f); lineTo(170f, 145f); lineTo(360f, 380f)
                lineTo(490f, 200f); lineTo(640f, 380f); lineTo(640f, 480f)
                lineTo(0f, 480f); close()
            }
            canvas.drawPath(landscape, paint)
            paint.color = android.graphics.Color.rgb(38, 49, 57)
            paint.textSize = 26f
            canvas.drawText(label, 24f, 44f, paint)
            // 中央色标保留精确像素断言；四角、横纵边界和细节供预览/缩放验收。
            paint.color = marker
            canvas.drawRect(296f, 216f, 344f, 264f, paint)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        var removedPath: String? = null
        fun assertDisplayedImage(position: Int, color: Int) {
            rule.onNodeWithTag("attachment-preview-position")
                .assertTextEquals("图片 $position / 2").assertIsDisplayed()
            rule.waitUntil(5000L) {
                rule.onAllNodesWithContentDescription("第 $position 张附件的大图预览")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val actual = rule.onNodeWithTag("attachment-preview-image").assertIsDisplayed()
                .captureToImage().toPixelMap()
            org.junit.Assert.assertEquals("实际图片像素必须对应当前附件",
                color, actual[actual.width / 2, actual.height / 2].toArgb())
        }
        try {
            writePreviewFixture(first, android.graphics.Color.RED, "Preview 01 · 640 × 480")
            writePreviewFixture(second, android.graphics.Color.BLUE, "Preview 02 · 640 × 480")
            rule.setContent {
                var paths by remember { mutableStateOf(listOf(first.absolutePath, second.absolutePath)) }
                CodexTheme {
                    Column {
                        Text("附件数量：" + paths.size)
                        LocalImageAttachmentStrip(paths, onRemove = {
                            removedPath = it
                            paths = paths - it
                        })
                    }
                }
            }
            rule.waitUntil(5000L) {
                rule.onAllNodesWithContentDescription("预览附件图片 1").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("预览附件图片 1").performTouchInput { click() }
            assertDisplayedImage(1, android.graphics.Color.RED)
            captureComponentEvidence("audit-attachment-preview-android.png")
            rule.onNodeWithTag("attachment-zoom-in").performClick()
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("150%")
            rule.onNodeWithTag("attachment-preview-reset").performClick()
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("100%")
            rule.onNodeWithTag("attachment-previous").assertIsNotEnabled()
            rule.onNodeWithTag("attachment-next").assertIsEnabled().performTouchInput { click() }
            assertDisplayedImage(2, android.graphics.Color.BLUE)
            rule.onNodeWithTag("attachment-next").assertIsNotEnabled()
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("100%")
            rule.onNodeWithTag("attachment-zoom-in").performClick()
            rule.onNodeWithTag("attachment-previous").assertIsEnabled().performTouchInput { click() }
            assertDisplayedImage(1, android.graphics.Color.RED)
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("100%")
            rule.onNodeWithTag("attachment-next").performTouchInput { click() }
            assertDisplayedImage(2, android.graphics.Color.BLUE)
            rule.onNodeWithContentDescription("移除当前图片").performClick()
            rule.onNodeWithTag("attachment-preview").assertDoesNotExist()
            rule.onNodeWithText("附件数量：1").assertExists()
            rule.runOnIdle { org.junit.Assert.assertEquals(second.absolutePath, removedPath) }
        } finally {
            bitmap.recycle()
            first.delete()
            second.delete()
        }
    }

    @Test fun chatSetupGuideOpensOnlyTheConfirmedMissingPrerequisite() {
        var hint by mutableStateOf<ChatSetupHint?>(ChatSetupPolicy.hint(true,
            org.json.JSONObject().put("account", org.json.JSONObject.NULL)
                .put("requiresOpenaiAuth", true), null))
        var destination: String? = null
        rule.setContent {
            CodexTheme { ChatSetupGuide(hint) { destination = it } }
        }
        rule.onNodeWithTag("chat-setup-account").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { org.junit.Assert.assertEquals("account", destination) }
        rule.runOnIdle {
            hint = ChatSetupPolicy.hint(true,
                org.json.JSONObject().put("account", org.json.JSONObject.NULL)
                    .put("requiresOpenaiAuth", false),
                org.json.JSONObject().put("data", org.json.JSONArray())
                    .put("nextCursor", org.json.JSONObject.NULL))
        }
        rule.onNodeWithTag("chat-setup-account").assertDoesNotExist()
        rule.onNodeWithTag("chat-setup-providers").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { org.junit.Assert.assertEquals("providers", destination) }
        rule.runOnIdle { hint = ChatSetupPolicy.hint(false, null, null) }
        rule.onNodeWithTag("chat-setup-providers").assertDoesNotExist()
    }

    @Test fun undoSnackbarUsesRealReceiptAndDispatchesExactInverseOnce() {
        val gate = ThreadUndoGate()
        val ticket = gate.begin("thread/archive", listOf("a", "b"))
        val offer = gate.complete(ticket, listOf("a"))!!
        var executed: ThreadUndoOffer? = null
        rule.setContent {
            CodexTheme {
                val host = remember { androidx.compose.material3.SnackbarHostState() }
                androidx.compose.material3.SnackbarHost(hostState = host)
                LaunchedEffect(Unit) {
                    presentActionFeedback(host, ActionFeedbackReceipt("会话已归档", undo = offer),
                        gate::isCurrent) {
                        if (gate.consume(it)) executed = it
                    }
                }
            }
        }
        rule.onNodeWithText("撤销").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { org.junit.Assert.assertEquals(offer, executed) }
        rule.onNodeWithText("撤销").assertDoesNotExist()
    }

    @Test fun messageMenuOpensActualTextReader() {
        rule.setContent {
            CodexTheme {
                TimelineStepCard(TimelineItem("test-message-reader", "agentMessage", "可核对的原始内容"))
            }
        }
        rule.onNodeWithTag("timeline-header-test-message-reader").performClick()
        rule.onNodeWithText("全文阅读").performClick()
        rule.onNodeWithTag("full-text-search").assertDoesNotExist()
        setQueryClipboard("原始")
        rule.onNodeWithTag("full-text-clipboard-query").performClick()
        rule.onNodeWithText("第 1 / 1 处").assertExists()
    }

    @Test fun fileEditorProtectsUnsavedChangesAndSavesRealFile() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(context.cacheDir, "editor-test-" + java.util.UUID.randomUUID())
        check(root.mkdir())
        val file = java.io.File(root, "notes-" + java.util.UUID.randomUUID() + ".txt").apply { writeText("原始内容") }
        val store = WorkspaceEditorDraftStore(java.io.File(context.filesDir, "editor-drafts"))
        val open = mutableStateOf(true)
        fun waitForEditor() {
            rule.waitUntil(5000L) {
                rule.onAllNodesWithTag("workspace-editor-content").fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitForClose() {
            rule.waitUntil(5000L) {
                rule.onAllNodesWithTag("workspace-file-editor").fetchSemanticsNodes().isEmpty()
            }
        }
        try {
            rule.setContent {
                CodexTheme {
                    if (open.value) FileEditor(file, root, onBack = { open.value = false })
                }
            }
            waitForEditor()
            rule.onNodeWithTag("workspace-editor-save").assertIsNotEnabled()
            rule.onNodeWithTag("workspace-editor-content").performTextReplacement("修改内容")
            rule.onNodeWithContentDescription("返回文件列表").performClick()
            rule.onNodeWithText("保留草稿并返回？").assertExists()
            captureComponentEvidence("audit-file-editor-android.png")
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("workspace-editor-content").assertExists()
            rule.onNodeWithTag("workspace-editor-save").assertIsEnabled()
            org.junit.Assert.assertEquals("原始内容", file.readText())
            org.junit.Assert.assertNull(store.read(root, file))

            // Retain the edit on disk without modifying the actual file.
            rule.onNodeWithContentDescription("返回文件列表").performClick()
            rule.onNodeWithText("保留草稿").performClick()
            waitForClose()
            org.junit.Assert.assertEquals("原始内容", file.readText())
            org.junit.Assert.assertEquals(WorkspaceEditorDraft("原始内容", "修改内容"), store.read(root, file))

            // Reopening reads the persisted draft, not a remembered in-memory editor.
            rule.runOnIdle { open.value = true }
            waitForEditor()
            rule.onNodeWithText("已恢复编辑草稿").assertExists()
            rule.onNodeWithTag("workspace-editor-content").assertTextContains("修改内容")
            rule.onNodeWithTag("workspace-editor-save").assertIsEnabled().performClick()
            rule.waitUntil(5000L) {
                rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
            }
            org.junit.Assert.assertEquals("修改内容", file.readText())
            org.junit.Assert.assertNull(store.read(root, file))

            // Discard remains explicit, with a separate destructive confirmation.
            rule.onNodeWithTag("workspace-editor-content").performTextReplacement("需要放弃")
            rule.onNodeWithTag("workspace-editor-discard").performClick()
            rule.onNodeWithText("放弃未保存的修改？").assertExists()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("workspace-editor-content").assertTextContains("需要放弃")
            rule.onNodeWithTag("workspace-editor-discard").performClick()
            rule.onNodeWithText("放弃修改").performClick()
            waitForClose()
            org.junit.Assert.assertEquals("修改内容", file.readText())
            org.junit.Assert.assertNull(store.read(root, file))
        } finally {
            store.clear(root, file)
            root.deleteRecursively()
        }
    }

    @Test fun messageHeaderLongPressPreservesReaderAction() {
        var action: Pair<String, MessageTurnAction>? = null
        rule.setContent {
            CodexTheme {
                TimelineStepCard(TimelineItem("long-press", "agentMessage", "原始消息正文", "completed", turnId = "test-turn"),
                    onTurnAction = { item, selected -> action = item.turnId to selected })
            }
        }
        rule.onNodeWithTag("message-copy-long-press").assertExists()
        rule.onNodeWithTag("message-share-long-press").assertExists()
        rule.onNodeWithTag("message-retry-long-press").assertIsEnabled().performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals("test-turn" to MessageTurnAction.Retry, action) }
        rule.onNodeWithTag("timeline-header-long-press").performTouchInput { longClick() }
        rule.onNodeWithTag("message-fork-long-press").assertIsEnabled()
        captureComponentEvidence("audit-message-menu-android.png")
        rule.onNodeWithTag("message-fork-long-press").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals("test-turn" to MessageTurnAction.Fork, action) }
        rule.onNodeWithTag("timeline-header-long-press").performClick()
        rule.onNodeWithText("全文阅读").performClick()
        rule.onNode(hasText("原始消息正文") and hasAnyAncestor(hasTestTag("full-text-reader"))).assertExists()
    }

    @Test fun updateFeedbackUsesRealPhaseAndPercentage() {
        rule.setContent {
            var status by remember { mutableStateOf(AppUpdateState(UpdatePhase.DOWNLOADING, "正在下载", progress = 47)) }
            CodexTheme {
                Column {
                    AtlasUpdateStatus(status)
                    TextButton(onClick = { status = AppUpdateState(UpdatePhase.ERROR, "校验失败") }) {
                        Text("模拟真实失败状态")
                    }
                }
            }
        }
        rule.onNodeWithText("47%").assertExists()
        captureComponentEvidence("audit-update-progress-android.png")
        rule.onNodeWithText("模拟真实失败状态").performClick()
        rule.onNodeWithText("47%").assertDoesNotExist()
        rule.onNodeWithText("校验失败").assertExists()
    }


    @Test fun queueDragHandleInvokesDragRatherThanClickAndKeepsAccessibleMenu() {
        var clicks = 0
        var starts = 0
        var moved = 0f
        var ended = 0
        rule.setContent {
            CodexTheme {
                Column {
                    AtlasQueueDragHandle(1, 3, true, "first",
                        onClick = { clicks++ }, onStart = { starts++ },
                        onMove = { moved += it }, onEnd = { ended++ }, onCancel = {})
                    Text("第二项")
                    Text("第三项")
                }
            }
        }
        rule.onNodeWithTag("queue-drag-first").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals(1, clicks) }
        rule.onNodeWithTag("queue-drag-first").performTouchInput {
            down(center)
            advanceEventTime(650)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 70f))
            up()
        }
        rule.runOnIdle {
            org.junit.Assert.assertEquals(1, starts)
            org.junit.Assert.assertTrue(moved > 0f)
            org.junit.Assert.assertEquals(1, ended)
            org.junit.Assert.assertEquals(1, clicks)
        }
    }

    @Test fun crossPageSelectionCopiesExactMarkdownAcrossUnloadedItems() {
        val sample = "第一段**粗体**\n" + "正文\n".repeat(4000) + "\n[末尾](./file.md)"
        lateinit var context: android.content.Context
        rule.setContent {
            context = androidx.compose.ui.platform.LocalContext.current
            CodexTheme { FullTextReaderDialog("长回复", sample, CopyContentFormat.Markdown, onDismiss = {}) }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("full-text-chunk-0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("full-text-range-toggle").performClick()
        rule.onNodeWithTag("full-text-range-start-0").performClick()
        val last = ReaderTextIndex.chunks(sample).lastIndex
        rule.onNodeWithTag("full-text-reader-list").performScrollToIndex(last)
        rule.onNodeWithTag("full-text-range-end-$last").performClick()
        rule.onNodeWithTag("full-text-range-copy").assertIsEnabled().performClick()
        rule.waitUntil(10_000) {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
            clip?.getItemAt(0)?.text?.toString() == sample
        }
    }

    @Test fun fullTextSearchScrollsToDistantMatchWithinSameChunk() {
        val sample = "needle\n" + "one two three four\n".repeat(90) + "needle"
        rule.setContent {
            CodexTheme { FullTextReaderDialog("长文本定位", sample, onDismiss = {}) }
        }
        setQueryClipboard("needle")
        rule.onNodeWithTag("full-text-clipboard-query").performClick()
        rule.onNodeWithText("第 1 / 2 处").assertExists()
        val list = rule.onNodeWithTag("full-text-reader-list")
        fun scrollValue(): Float = list.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val start = scrollValue()
        rule.onNodeWithContentDescription("下一个匹配").performClick()
        rule.onNodeWithText("第 2 / 2 处").assertExists()
        rule.waitUntil(5000L) { scrollValue() > start }
    }

    private fun captureComponentEvidence(name: String) {
        rule.waitForIdle()
        captureVerifiedDisplayScreenshot(name)
    }


    @Test fun composerShortcutOpensRealCallbackOnlyWhenSelected() {
        var opened = 0
        rule.setContent {
            var draft by remember { mutableStateOf("/mod") }
            CodexTheme {
                Column {
                    AtlasComposerShortcuts(draft, listOf(
                        ComposerShortcut("model", "选择模型", "可用模型", CodexGlyph.Memory, onOpen = { opened++ }),
                        ComposerShortcut("mode", "执行模式", "运行中暂不可改", CodexGlyph.Tune,
                            enabled = false, onOpen = { opened += 10 })
                    )) { draft = ""; it.onOpen() }
                    Text("草稿：" + draft)
                }
            }
        }
        rule.onNodeWithTag("composer-shortcut-mode").assertIsNotEnabled()
        rule.runOnIdle { org.junit.Assert.assertEquals(0, opened) }
        rule.onNodeWithTag("composer-shortcut-model").performClick()
        rule.onNodeWithTag("composer-shortcuts").assertDoesNotExist()
        rule.onNodeWithText("草稿：").assertExists()
        rule.runOnIdle { org.junit.Assert.assertEquals(1, opened) }
    }

    @Test fun terminalTouchHistoryRestoresOriginalDraftWithoutExecuting() {
        rule.setContent {
            var text by remember { mutableStateOf("unfinished") }
            var position by remember { mutableStateOf(TerminalHistoryPosition()) }
            CodexTheme {
                Column {
                    Text(text)
                    AtlasTerminalHistoryControls(text, listOf("git status", "pwd"), position, true,
                        onChoice = { text = it.command; position = it.position }, onOpenHistory = {})
                }
            }
        }
        rule.onNodeWithTag("terminal-history-previous").performClick()
        rule.onNodeWithText("git status").assertExists()
        rule.onNodeWithTag("terminal-history-previous").performClick()
        rule.onNodeWithText("pwd").assertExists()
        rule.onNodeWithTag("terminal-history-next").performClick()
        rule.onNodeWithText("git status").assertExists()
        rule.onNodeWithTag("terminal-history-next").performClick()
        rule.onNodeWithText("unfinished").assertExists()
        rule.onNodeWithTag("terminal-history-next").assertIsNotEnabled()
    }

    @Test fun dailyUsagePeriodSelectionDoesNotTurnMissingTokensIntoZero() {
        rule.setContent {
            CodexTheme {
                AtlasDailyUsageChart(listOf(
                    org.json.JSONObject().put("startDate", "2026-11-01").put("tokens", 13),
                    org.json.JSONObject().put("startDate", "2026-11-24").put("tokens", 0),
                    org.json.JSONObject().put("startDate", "2026-11-23")
                ))
            }
        }
        rule.onNodeWithText("未提供").assertExists()
        rule.onNodeWithText("11-01").assertDoesNotExist()
        rule.onNodeWithTag("usage-period-30").performClick()
        rule.onNodeWithText("11-01").assertExists()
        rule.onNodeWithText("13").assertExists()
    }

    @Test fun unifiedControlSystemRetainsSingleChoiceFieldClearAndDisabledAction() {
        assertControlSystemInteraction(rule)
    }
    @Test fun v8BusyButtonRetainsWidthAndSecretCanBeHidden() {
        assertV8ComponentStates(rule)
    }

    @Test fun v8ControlBordersHaveAccessibleContrastInBothThemes() {
        assertV8ThemeContrast(rule)
    }

    @Test fun v8RouteMotionConvergesAndReducedMotionSnaps() {
        assertV8RouteMotion(rule)
    }
    @Test fun answerVersionSwitcherKeepsNavigationInTheMessage() {
        var chosen = ""
        val state = AnswerVersionState().add("original", "turn-one", "retry").bind("retry", "turn-two")
        rule.setContent {
            CodexTheme {
                TimelineStepCard(TimelineItem("version-message", "agentMessage", "第二版回答", "completed", turnId = "turn-two"),
                    version = state.choice("retry", "turn-two"), onVersion = { chosen = it })
            }
        }
        rule.onNodeWithText("2 / 2").assertExists()
        rule.onNodeWithTag("answer-next-version-message").assertIsNotEnabled()
        rule.onNodeWithTag("answer-previous-version-message").assertIsEnabled().performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals("original", chosen) }
        rule.onNodeWithTag("message-copy-version-message").assertExists()
    }


    @Test fun nativeUserMessageUsesMobileBubble() {
        rule.setContent {
            CodexTheme { TimelineActivityGroup(listOf(TimelineItem("native-user", "userMessage", "任务", "completed"))) }
        }
        rule.onNodeWithTag("chat-user-message").assertExists()
        rule.onNodeWithText("任务").assertExists()
        rule.onNodeWithTag("chat-activity-group").assertDoesNotExist()
    }

    @Test fun liveReplyShowsToolRowsAndNativeRichContent() {
        val fence = "```"
        val tools = listOf(TimelineItem("live-tool", "commandExecution", "读取仓库", "completed", turnId = "turn"))
        val running = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            CodexTheme {
                androidx.compose.foundation.layout.Column {
                    TimelineActivityGroup(tools, liveProcess = running.value)
                    NativeMessageMarkdown(fence + "codex-metrics\n文件数|11,404\n开放 PR|3\n" + fence)
                    NativeMessageMarkdown(fence + "codex-flow\n读取\n检查\n" + fence)
                }
            }
        }
        rule.onNodeWithText("读取仓库").assertExists()
        rule.onNodeWithTag("message-rich-metrics").assertExists()
        rule.onNodeWithText("11,404").assertExists()
        rule.onNodeWithTag("message-rich-flow").assertExists()
        rule.runOnIdle { running.value = false }
        rule.onNodeWithText("读取仓库").assertDoesNotExist()
        rule.onNodeWithTag("chat-activity-group").performClick()
        rule.onNodeWithText("读取仓库").assertExists()
    }

    @Test fun progressiveReplyShowsFragmentsThenOneAnswerAction() {
        val partial = TimelineItem("partial-ui", "agentMessage", "第一部分\n\n", "completed",
            turnId = "progressive-ui", phase = "partial_answer")
        val commentary = TimelineItem("commentary-ui", "agentMessage", "继续检查", "completed",
            turnId = "progressive-ui", phase = "commentary")
        val final = TimelineItem("final-ui", "agentMessage", "第二部分", "completed",
            turnId = "progressive-ui", phase = "final_answer")
        val running = androidx.compose.runtime.mutableStateOf(true)
        val events = androidx.compose.runtime.mutableStateOf(listOf(partial, commentary))
        rule.setContent {
            CodexTheme {
                androidx.compose.foundation.layout.Column {
                    val rows = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(events.value),
                        running.value, if (running.value) "progressive-ui" else null, false)
                    rows.forEach { row ->
                        androidx.compose.runtime.key(row.key) {
                            TimelineActivityGroup(row.group, showMessageActions = row.showActions,
                                actionItem = row.actionItem, forceActivity = row.forceActivity, note = row.note, liveProcess = row.liveProcess)
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("第一部分").assertExists()
        rule.onNodeWithTag("chat-commentary").assertExists()
        rule.onNodeWithTag("message-copy-partial-ui").assertDoesNotExist()
        rule.runOnIdle { events.value = events.value + final; running.value = false }
        rule.onNodeWithTag("message-copy-final-ui").assertExists()
        rule.onNodeWithTag("message-copy-partial-ui").assertDoesNotExist()
        rule.onNodeWithTag("chat-commentary").assertDoesNotExist()
        rule.onNodeWithTag("timeline-header-final-ui").performClick()
        rule.onNodeWithText("全文阅读").performClick()
        rule.onNodeWithTag("full-text-reader").assertExists()
        setQueryClipboard("第二部分")
        rule.onNodeWithTag("full-text-clipboard-query").performClick()
        rule.onNodeWithText("第 1 / 1 处").assertExists()
    }

    @Test fun messageCopyWritesBothFormatsWithoutOpeningMenu() {
        val source = "**复制验证**"
        lateinit var context: android.content.Context
        rule.setContent {
            context = androidx.compose.ui.platform.LocalContext.current
            CodexTheme {
                TimelineStepCard(TimelineItem("copy-ui", "agentMessage", source, "completed", turnId = "turn"))
            }
        }
        rule.onNodeWithTag("message-copy-copy-ui").performClick()
        rule.waitUntil(5000) {
            rule.runOnIdle {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                clip != null && clip.itemCount > 0 && clip.getItemAt(0).text?.toString() == source &&
                    clip.getItemAt(0).htmlText?.contains("<strong>复制验证</strong>") == true
            }
        }
        rule.onNodeWithText("复制纯文本").assertDoesNotExist()
        rule.onNodeWithTag("message-actions-copy-ui").assertExists()
    }

    @Test fun tableCopyRangeKeepsOnlySelectedCells() {
        lateinit var context: android.content.Context
        rule.setContent {
            context = androidx.compose.ui.platform.LocalContext.current
            CodexTheme { NativeMessageMarkdown("| A | B |\n| --- | --- |\n| 排除 | 保留 |") }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("message-table-select").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("message-table-select").performClick()
        rule.onNodeWithTag("message-table-cell-1-1").performClick()
        rule.onNodeWithTag("message-table-cell-1-1").performClick()
        rule.onNodeWithTag("message-table-copy").performClick()
        rule.waitUntil(5000) {
            rule.runOnIdle {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                val value = clip?.getItemAt(0)?.text?.toString().orEmpty()
                value.contains("保留") && !value.contains("排除") && !value.contains(" A ")
            }
        }
    }
}
