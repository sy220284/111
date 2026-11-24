package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
                    searchable = true)
            }
        }
        rule.onNodeWithTag("choice-option-alpha").assertIsSelected()
        rule.onNodeWithTag("choice-search").performTextInput("Beta")
        rule.onNodeWithTag("choice-option-alpha").assertDoesNotExist()
        rule.onNodeWithTag("choice-option-beta").performClick()
        rule.onNodeWithTag("choice-option-beta").assertIsSelected()
        rule.onNodeWithContentDescription("清除搜索词").performClick()
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
        rule.onNodeWithTag("full-text-search").performTextInput("alpha")
        rule.onNodeWithText("第 1 / 2 处").assertExists()
        rule.onNodeWithContentDescription("上一个匹配").performClick()
        rule.onNodeWithText("第 2 / 2 处").assertExists()
        captureComponentEvidence("audit-full-text-match-android35.png")
        rule.onNodeWithContentDescription("下一个匹配").performClick()
        rule.onNodeWithText("第 1 / 2 处").assertExists()
        rule.onNodeWithContentDescription("清除搜索词").performClick()
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
                    AtlasFormField(address, { address = it }, "服务地址",
                        modifier = androidx.compose.ui.Modifier.testTag("test-service-address"),
                        error = if (address.isNotBlank() && !ProviderAddressPolicy.valid(address))
                            "请输入有效的 HTTP 或 HTTPS 地址" else null)
                    TextButton(onClick = {}, enabled = ProviderAddressPolicy.valid(address),
                        modifier = androidx.compose.ui.Modifier.testTag("test-save-address")) { Text("保存") }
                }
            }
        }
        rule.onNodeWithTag("test-save-address").assertIsNotEnabled()
        rule.onNodeWithTag("test-service-address").performTextInput("not-an-address")
        rule.onNodeWithText("请输入有效的 HTTP 或 HTTPS 地址").assertExists()
        rule.onNodeWithTag("test-save-address").assertIsNotEnabled()
        rule.onNodeWithContentDescription("清除服务地址").performClick()
        rule.onNodeWithTag("test-service-address").performTextInput("https://example.com/v1")
        rule.onNodeWithText("请输入有效的 HTTP 或 HTTPS 地址").assertDoesNotExist()
        rule.onNodeWithTag("test-save-address").assertIsEnabled()
    }

    @Test fun attachmentPreviewSupportsZoomSwitchResetAndRemove() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val first = java.io.File.createTempFile("preview-first", ".png", context.cacheDir)
        val second = java.io.File.createTempFile("preview-second", ".png", context.cacheDir)
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        var selectedByClick: String? = null
        try {
            first.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            second.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            rule.setContent {
                var paths by remember { mutableStateOf(listOf(first.absolutePath, second.absolutePath)) }
                var selected by remember { mutableStateOf(first.absolutePath) }
                CodexTheme {
                    Column {
                        Text("附件数量：" + paths.size)
                        if (selected in paths) AttachmentPreviewDialog(paths, selected,
                            onSelect = { selectedByClick = it; selected = it }, onDismiss = { selected = "" },
                            onRemove = { removed -> paths = paths - removed; selected = "" })
                    }
                }
            }
            rule.waitUntil(5000L) {
                rule.onAllNodesWithTag("attachment-preview-image").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("图片 1 / 2").assertExists()
            rule.onNodeWithTag("attachment-zoom-in").performClick()
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("150%")
            captureComponentEvidence("audit-attachment-preview-android35.png")
            rule.onNodeWithTag("attachment-preview-reset").performClick()
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("100%")
            rule.onNodeWithTag("attachment-previous").assertIsNotEnabled()
            rule.onNodeWithTag("attachment-next").assertIsEnabled().performTouchInput { click() }
            rule.runOnIdle { org.junit.Assert.assertEquals(second.absolutePath, selectedByClick) }
            rule.waitUntil(5000L) {
                rule.onAllNodesWithText("图片 2 / 2").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("图片 2 / 2").assertIsDisplayed()
            rule.onNodeWithTag("attachment-next").assertIsNotEnabled()
            rule.waitUntil(5000L) {
                rule.onAllNodesWithTag("attachment-preview-image").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("attachment-zoom-level").assertTextEquals("100%")
            rule.onNodeWithTag("attachment-previous").assertIsEnabled().performTouchInput { click() }
            rule.runOnIdle { org.junit.Assert.assertEquals(first.absolutePath, selectedByClick) }
            rule.waitUntil(5000L) {
                rule.onAllNodesWithText("图片 1 / 2").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("移除当前图片").performClick()
            rule.onNodeWithTag("attachment-preview").assertDoesNotExist()
            rule.onNodeWithText("附件数量：1").assertExists()
        } finally {
            bitmap.recycle()
            first.delete()
            second.delete()
        }
    }

    @Test fun messageMenuOpensActualTextReader() {
        rule.setContent {
            CodexTheme {
                TimelineStepCard(TimelineItem("test-message-reader", "agentMessage", "可核对的原始内容"))
            }
        }
        rule.onNodeWithTag("timeline-more-test-message-reader").performClick()
        rule.onNodeWithText("全文阅读").performClick()
        rule.onNodeWithTag("full-text-search").assertExists()
        rule.onNodeWithTag("full-text-search").performTextInput("原始")
        rule.onNodeWithText("第 1 / 1 处").assertExists()
    }

    @Test fun fileEditorProtectsUnsavedChangesAndSavesRealFile() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(context.cacheDir, "editor-test-" + java.util.UUID.randomUUID())
        check(root.mkdir())
        val file = java.io.File(root, "notes.txt").apply { writeText("原始内容") }
        try {
            rule.setContent {
                var open by remember { mutableStateOf(true) }
                CodexTheme {
                    if (open) FileEditor(file, root, onBack = { open = false })
                }
            }
            rule.waitUntil(5000L) {
                rule.onAllNodesWithTag("workspace-editor-content").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("workspace-editor-save").assertIsNotEnabled()
            rule.onNodeWithTag("workspace-editor-content").performTextReplacement("修改内容")
            rule.onNodeWithContentDescription("返回文件列表").performClick()
            rule.onNodeWithText("放弃未保存的修改？").assertExists()
            captureComponentEvidence("audit-file-editor-android35.png")
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("workspace-editor-save").assertIsEnabled().performClick()
            rule.waitUntil(5000L) { file.readText() == "修改内容" }
            rule.waitUntil(5000L) {
                rule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("返回文件列表").performClick()
            rule.onNodeWithTag("workspace-file-editor").assertDoesNotExist()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun messageHeaderLongPressPreservesReaderAction() {
        rule.setContent {
            CodexTheme { TimelineStepCard(TimelineItem("long-press", "agentMessage", "原始消息正文")) }
        }
        rule.onNodeWithTag("timeline-header-long-press").performTouchInput { longClick() }
        rule.onNodeWithText("复制内容").assertExists()
        captureComponentEvidence("audit-message-menu-android35.png")
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
        captureComponentEvidence("audit-update-progress-android35.png")
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

    @Test fun fullTextSearchScrollsToDistantMatchWithinSameChunk() {
        val sample = "needle\n" + "one two three four\n".repeat(90) + "needle"
        rule.setContent {
            CodexTheme { FullTextReaderDialog("长文本定位", sample, onDismiss = {}) }
        }
        rule.onNodeWithTag("full-text-search").performTextInput("needle")
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
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(directory, name).outputStream().use { output ->
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { screenshot.recycle() }
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

}
