package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ConversationPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val status by vm.bridge.status.collectAsState()
    val thread = vm.activeThread.collectAsState().value
    val busy = vm.running.collectAsState().value
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeTurn = vm.activeTurn.collectAsState().value
    val steering by vm.steerBusy.collectAsState()
    val loading by vm.loading.collectProjection { it.intersect(setOf("interrupt", "queueWrite")) }
    val latestDiff by vm.latestDiff.collectAsState()
    val models by vm.data.collectEntries("account", "models", "goal:$thread", "queue:$thread", "tokenUsage:$thread")
    val setupHint = if (thread == null) ChatSetupPolicy.hint(
        status == EngineStatus.Connected, models["account"], models["models"]) else null
    val errors by vm.errors.collectEntries("protocol", "planApproval", "timeline", "conversation", "steer", "interrupt", "drafts", "draftRecovery", "composerDocuments", "attachments", "queueWrite")
    val prompts by vm.bridge.prompts.collectAsState()
    val selectedWorkspace by vm.activeWorkspace.collectAsState()
    val draftOwner = vm.composerOwner(thread, selectedWorkspace)
    val composerDrafts by vm.composerDrafts.collectEntries(draftOwner)
    val snapshot = composerDrafts[draftOwner] ?: ComposerDraft()
    val draft = snapshot.text
    val attachedImages = snapshot.images
    val selectedModel by vm.modelId.collectAsState()
    val permissionSaving by vm.securitySaveBusy.collectAsState()
    val transferBusy by AppDataTransfer.state.collectProjection { it.busy }
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val documentOwner by vm.composerContent.documentOwner.collectAsState()
    val documentBusy = documentOwner == draftOwner
    var composerMenu by remember(draftOwner) { mutableStateOf("") }
    var referenceQuery by remember(draftOwner) { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var showContextTools by remember(thread) { mutableStateOf(false) }
    var sessionAction by remember(draftOwner) { mutableStateOf("") }
    var showTerminalSheet by remember { mutableStateOf(false) }
    var followUpMode by remember(draftOwner, busy, snapshot.hasInput) { mutableStateOf("queue") }
    val renderedFollowUpMode = followUpMode
    var pendingContextRoute by remember { mutableStateOf<String?>(null) }
    var pendingImagePick by remember { mutableStateOf(false) }
    var showEngineStatus by remember { mutableStateOf(false) }
    var showApprovalQuick by remember { mutableStateOf(false) }
    var stopRequestedForTurn by remember { mutableStateOf<String?>(null) }
    val stopping = busy && (("interrupt" in loading) ||
        (stopRequestedForTurn != null && stopRequestedForTurn == activeTurn &&
            errors["interrupt"] == null))
    val imported by vm.attachmentImport.collectAsState()
    val attachmentBusy = imported.inProgress &&
        imported.threadKey == draftOwner
    val attachmentError = if (imported.threadKey == draftOwner)
        imported.message else ""

    // 手机竖屏输入法打开时收起次要控件，优先保留输入和发送区。
    val compactWorkArea = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val selectedMode by vm.selectedMode.collectAsState()
    val goal = thread?.let { models["goal:$it"]?.optJSONObject("goal") }
    val queueData = thread?.let { models["queue:$it"] }
    val queuedInputs = remember(queueData) { records(queueData) }
    val queuePaused by vm.queuePausedThreads.collectProjection { thread != null && thread in it }
    val queueReadBusy by vm.loading.collectProjection { "queue:$thread" in it }
    val queueReadError by vm.errors.collectProjection { it["queue:$thread"] }
    val modelData = models["models"]
    val availableModels = remember(modelData) { records(modelData) }
    val diffSummary by rememberDiffSummary(latestDiff)
    LaunchedEffect(thread) {
        if (thread != null) {
            vm.refresh("goal:$thread", "thread/goal/get", JSONObject().put("threadId", thread))
            vm.refresh("queue:$thread", "thread/queue/list",
                JSONObject().put("threadId", thread).put("limit", 100))
        }
    }
    val planApprovalBusy by vm.planApprovalBusy.collectAsState()
    var confirmPlan by remember(thread) { mutableStateOf(false) }
    val latestPlan = if (selectedMode == "plan" && !busy && !switching && thread != null) {
        val completedPlan by vm.timeline.collectProjection { timeline ->
            // Search backwards once when approval is relevant, not on every streaming delta.
            val index = timeline.indexOfLast {
                it.kind == "plan" && it.text.isNotBlank() && !it.status.contains("stream", ignoreCase = true)
            }
            timeline.getOrNull(index)?.takeIf {
                (index + 1 until timeline.size).none { next -> timeline[next].kind == "user" }
            }
        }
        completedPlan
    } else null
    val offerPlanApproval = latestPlan != null
    val keyboard = LocalSoftwareKeyboardController.current
    // Pickers use the actual remaining quota, so no selected image silently vanishes.
    val oneImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.importPickedImages(listOfNotNull(uri)) }
    val twoImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { selected -> vm.importPickedImages(selected) }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        vm.composerContent.finishCamera(success)
    }
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = if (result.resultCode == android.app.Activity.RESULT_OK)
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        else null
        vm.composerContent.finishVoice(words)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.composerContent.importDocuments(uris)
    }
    val threeImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(LocalImageAttachments.MAX_FILES)
    ) { selected -> vm.importPickedImages(selected) }
    // Page-owned handoff: old ModalBottomSheet has left composition before next page opens.
    LaunchedEffect(showContextTools, pendingContextRoute) {
        if (!showContextTools) {
            pendingContextRoute?.let { destination ->
                pendingContextRoute = null
                navigate(destination)
            }
        }
    }
    LaunchedEffect(pendingImagePick) {
        if (pendingImagePick) {
            pendingImagePick = false
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            when ((LocalImageAttachments.MAX_FILES - attachedImages.size).coerceAtLeast(0)) {
                0 -> vm.reportFullAttachmentSelection(thread)
                1 -> oneImagePicker.launch(request)
                2 -> twoImagePicker.launch(request)
                else -> threeImagePicker.launch(request)
            }
        }
    }
    // 窗口已配置 adjustResize，键盘展开时由系统压缩工作区。
    // 不再次叠加 IME 底部内边距，防止输入栏和发送按钮整体被推离可见区域。
    Column(Modifier.fillMaxSize()) {
        if (goal != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                    .testTag("chat-goal-banner").clickable { navigate("goals") },
                color = Ink.elevated,
                shape = RoundedCornerShape(15.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CodexIcon(CodexGlyph.Flag, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("任务目标 · " + PresentationPolicy.goalStatus(goal.optString("status")),
                            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                        Text(goal.optString("objective"), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                    CodexIcon(CodexGlyph.ChevronRight, null)
                }
            }
        }
        if (latestDiff.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                    .testTag("chat-changes-summary"),
                color = Ink.elevated, shape = RoundedCornerShape(15.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CodexIcon(CodexGlyph.Diff, null, tint = Ink.copper)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("代码变更", style = MaterialTheme.typography.titleSmall)
                        Text(diffSummary?.let { "${it.files} 个文件 · +${it.additions} / −${it.deletions}" }
                            ?: "正在整理代码变更…",
                            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    }
                    CodexTextAction(onClick = { navigate("diff") }) { Text("查看") }
                    CodexTextAction(onClick = { navigate("review") }, enabled = !busy) {
                        Text("审查")
                    }
                }
            }
        }
        CodexReveal(visible = prompts.isNotEmpty() || busy || switching) {
            if (switching) {
                CodexNotice("正在载入会话及执行记录，请稍候…",
                    NoticeTone.Info, Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            } else if (prompts.isNotEmpty()) {
                CodexNotice(
                    "当前任务有 ${prompts.size} 项待审批操作，确认后才能继续。",
                    NoticeTone.Warning, Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    actionLabel = "立即处理", onAction = { showApprovalQuick = true }
                )
            } else if (busy) {
                ConversationExecutionNotice(vm, stopping)
            }
        }
        (if (status == EngineStatus.Connected) errors["protocol"] else null)?.let { reason ->
            CodexNotice(reason, NoticeTone.Warning,
                Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                actionLabel = "查看诊断", onAction = { navigate("diagnostics") })
        }
        key(thread) {
            ConversationTimelineViewport(vm, thread, switching, busy, firstThreadStarting,
                compactWorkArea, setupHint, navigate, modifier = Modifier.weight(1f))
        }
        if (offerPlanApproval) {
            CodexNotice(
                "计划已生成。核对计划内容后，可批准并开始执行。",
                NoticeTone.Info,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                actionLabel = "批准方案并执行",
                onAction = { confirmPlan = true }
            )
        }
        errors["planApproval"]?.let { ErrorText(it) }
        errors["timeline"]?.let { reason ->
            CodexNotice(reason, NoticeTone.Warning,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        (if (status == EngineStatus.Connected) errors["conversation"] else null)?.let { reason ->
            CodexNotice(
                reason, NoticeTone.Error,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                actionLabel = when {
                    status is EngineStatus.Unavailable -> "重新连接"
                    !busy && !switching && snapshot.hasInput -> "重试发送"
                    else -> null
                },
                onAction = {
                    if (status is EngineStatus.Unavailable) vm.connect()
                    else if (!busy && !switching) {
                        vm.submitComposerDraft(snapshot, "queue", thread, activeTurn, expectedRunning = false)
                    }
                }
            )
        }
        errors["steer"]?.let { ErrorText(it) }
        errors["interrupt"]?.let { ErrorText(it) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp,
            vertical = if (compactWorkArea) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactWorkArea) 4.dp else 7.dp)) {
            errors["drafts"]?.let {
                CodexNotice(it, NoticeTone.Error)
            }
            errors["draftRecovery"]?.let {
                CodexNotice(it, NoticeTone.Warning)
            }
            if (attachmentBusy) {
                AtlasOperationProgress("正在导入图片 ${imported.imported}/${imported.requested}…",
                    progress = if (imported.requested > 0) imported.imported.toFloat() / imported.requested else null)
            }
            if (documentBusy) AtlasOperationProgress("正在导入本机文件")
            errors["composerDocuments"]?.let { ErrorText(it) }
            if (attachmentError.isNotBlank()) {
                ErrorText(attachmentError)
            }
            errors["attachments"]?.let { ErrorText(it) }
            errors["queueWrite"]?.let { ErrorText(it) }
            if (thread != null && queuedInputs.isNotEmpty()) {
                ComposerQueuePreview(thread, queuedInputs, busy, queuePaused && !busy,
                    hasMore = PresentationPolicy.meaningful(queueData?.optString("nextCursor")).isNotBlank(),
                    compact = compactWorkArea, canStart = !busy && !switching && !firstThreadStarting &&
                        !queueReadBusy && queueReadError == null && !steering && "queueWrite" !in loading && !transferBusy &&
                        !permissionSaving && !restarting && status == EngineStatus.Connected,
                    onManage = { keyboard?.hide(); navigate("queue") },
                    onStart = { vm.startQueuedComposer(thread) })
            }
            val optionsEnabled = !busy && !firstThreadStarting && !switching
            val tools = buildList {
                add(ComposerShortcut("files", "工作区文件", "浏览当前项目", CodexGlyph.Project,
                    enabled = !switching, onOpen = { keyboard?.hide(); navigate("files") }))
                add(ComposerShortcut("project", "当前项目 · " + PresentationPolicy.workspaceName(selectedWorkspace),
                    "选择已登记项目或工作目录", CodexGlyph.Folder, enabled = optionsEnabled,
                    onOpen = { keyboard?.hide(); composerMenu = "projects" }))
                add(ComposerShortcut("terminal", "项目终端", "打开实际命令输入", CodexGlyph.Terminal,
                    enabled = !switching, onOpen = { keyboard?.hide(); showTerminalSheet = true }))
                add(ComposerShortcut("model", "选择模型", "运行模型和思考强度", CodexGlyph.Memory,
                    enabled = optionsEnabled, onOpen = { keyboard?.hide(); showModels = true }))
                add(ComposerShortcut("mode", "协作方式", "选择先计划或执行", CodexGlyph.Tune,
                    enabled = optionsEnabled, onOpen = { keyboard?.hide(); showModes = true }))
                add(ComposerShortcut("review", "代码审查", "选择审查范围并确认", CodexGlyph.FactCheck,
                    enabled = optionsEnabled, onOpen = { keyboard?.hide(); navigate("review") }))
                add(ComposerShortcut("diff", "代码差异", "查看当前改动", CodexGlyph.Diff,
                    enabled = !switching, onOpen = { keyboard?.hide(); navigate("diff") }))
                if (thread != null) {
                    add(ComposerShortcut("context", "会话选项", "创建分支、整理上下文和回退", CodexGlyph.MoreHoriz,
                        enabled = !switching, onOpen = { keyboard?.hide(); showContextTools = true }))
                    add(ComposerShortcut("usage", "上下文容量", "查看当前会话容量", CodexGlyph.Insights,
                        onOpen = { keyboard?.hide(); composerMenu = "usage" }))
                    add(ComposerShortcut("goal", "任务目标", "查看和编辑当前会话目标", CodexGlyph.Flag,
                        enabled = !switching, onOpen = { keyboard?.hide(); navigate("goals") }))
                    add(ComposerShortcut("queue", "后续指令", "管理当前会话待执行队列", CodexGlyph.ViewList,
                        enabled = !switching, onOpen = { keyboard?.hide(); navigate("queue") }))
                    add(ComposerShortcut("fork", "创建会话分支", "", CodexGlyph.CallSplit,
                        enabled = optionsEnabled, onOpen = { keyboard?.hide(); sessionAction = "fork" }))
                    add(ComposerShortcut("compact", "整理上下文", "", CodexGlyph.Compress,
                        enabled = optionsEnabled, onOpen = { keyboard?.hide(); sessionAction = "compact" }))
                }
                add(ComposerShortcut("status", "运行状态", "连接与诊断", CodexGlyph.Info,
                    onOpen = { keyboard?.hide(); showEngineStatus = true }))
            }
            val sending = firstThreadStarting || steering || "queueWrite" in loading
            val sendBlockReason = when {
                permissionSaving -> "正在保存权限，请稍候"
                transferBusy -> "本机数据正在备份或恢复"
                restarting -> "运行核心正在重新连接"
                status != EngineStatus.Connected -> "运行核心尚未连接"
                switching -> "正在恢复会话"
                firstThreadStarting -> "正在建立新会话"
                attachmentBusy || documentBusy -> "正在导入附件"
                sending -> "正在提交指令，请勿重复发送"
                stopping -> "正在停止当前任务，请稍候"
                busy && thread == null -> "正在建立会话"
                busy && followUpMode == "steer" && activeTurn.isNullOrBlank() -> "正在等待执行回合"
                !snapshot.hasInput -> "输入指令或添加附件后即可发送"
                else -> ""
            }
            ConversationComposer(
                draft = draft, shortcuts = tools,
                onDraftChange = { vm.editComposerText(draftOwner, it) },
                attachedCount = attachedImages.size, connected = status == EngineStatus.Connected,
                running = busy, stopping = stopping,
                canStop = busy && !activeTurn.isNullOrBlank(),
                canSend = sendBlockReason.isBlank(), sending = sending,
                sendBlockReason = sendBlockReason,
                followUpMode = renderedFollowUpMode, onFollowUpModeChange = { followUpMode = it },
                onStop = {
                    if (thread != null && vm.activeThread.value == thread && vm.activeTurn.value == activeTurn) {
                        stopRequestedForTurn = activeTurn
                        vm.interruptThread(thread)
                    }
                },
                onSend = { vm.submitComposerDraft(snapshot, renderedFollowUpMode, thread, activeTurn, busy) },
                hasInput = snapshot.hasInput,
                imageImporting = attachmentBusy || documentBusy,
                onAddContent = { keyboard?.hide(); composerMenu = "add" },
                compactControls = compactWorkArea,
                modelLabel = ComposerModelPolicy.model(availableModels, selectedModel)
                    ?.optString("displayName")?.takeIf { it.isNotBlank() } ?: selectedModel,
                modeLabel = selectedMode,
                effortLabel = snapshot.options.effort.takeIf { it.isNotBlank() }?.let { PresentationPolicy.reasoningLabel(it) }.orEmpty(),
                optionsEnabled = optionsEnabled,
                onModel = { keyboard?.hide(); showModels = true },
                onMode = { keyboard?.hide(); showModes = true },
                onTaskOptions = { keyboard?.hide(); composerMenu = "tools" },
                onReference = { query -> referenceQuery = query; keyboard?.hide(); composerMenu = "reference" },
                statusContent = {
                    if (status is EngineStatus.Unavailable) {
                        CodexNotice("连接异常，草稿已保留", NoticeTone.Warning,
                            actionLabel = "重连", onAction = { vm.connectionRestarter.reconnect() })
                    } else if (status == EngineStatus.Starting) {
                        Text("正在连接运行核心…", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                },
                permissionContent = { ComposerPermissionControl(vm, optionsEnabled && !transferBusy) },
                attachmentContent = {
                    LocalImageAttachmentStrip(attachedImages) { vm.removeComposerImage(draftOwner, it) }
                    ComposerReferenceStrip(snapshot.references) { vm.composerContent.removeReference(draftOwner, it) }
                }
            )
            if (composerMenu == "add") CodexActionSheet(title = "添加内容", onDismiss = { composerMenu = "" },
                actions = buildList {
                    add(CodexSheetAction("照片", CodexGlyph.Photo, onClick = {
                        if (vm.beginImageSelection(draftOwner)) pendingImagePick = true
                    }, enabled = attachedImages.size < LocalImageAttachments.MAX_FILES,
                        description = "选择本机图片", testTag = "composer-add-photos"))
                    add(CodexSheetAction("文件", CodexGlyph.Description, onClick = {
                        if (vm.composerContent.beginDocument(draftOwner, selectedWorkspace)) {
                            try { documentPicker.launch(arrayOf("*/*")) }
                            catch (failure: Exception) {
                                vm.composerContent.importDocuments(emptyList())
                                vm.composerContent.pickerFailure(failure)
                            }
                        }
                    }, description = "导入当前项目并引用", testTag = "composer-add-files"))
                    add(CodexSheetAction("引用项目文件", CodexGlyph.Link,
                        onClick = { referenceQuery = null; composerMenu = "reference" }, testTag = "composer-add-reference"))
                    add(CodexSheetAction("技能", CodexGlyph.School,
                        onClick = { composerMenu = "skills" }, testTag = "composer-add-skill"))
                    if (vm.composerContent.canTakePhoto()) add(CodexSheetAction("拍照", CodexGlyph.Photo,
                        onClick = {
                            vm.composerContent.prepareCamera(draftOwner)?.let { uri ->
                                try { cameraPicker.launch(uri) }
                                catch (failure: Exception) {
                                    vm.composerContent.finishCamera(false)
                                    vm.composerContent.pickerFailure(failure)
                                }
                            }
                        }, enabled = attachedImages.size < LocalImageAttachments.MAX_FILES,
                        description = "拍摄并添加图片", testTag = "composer-add-camera"))
                    if (vm.composerContent.hasClipboardImage()) add(CodexSheetAction("粘贴图片", CodexGlyph.ContentCopy,
                        onClick = { vm.composerContent.pasteImage(draftOwner) },
                        enabled = attachedImages.size < LocalImageAttachments.MAX_FILES,
                        description = "添加剪贴板中的图片", testTag = "composer-add-paste"))
                    if (vm.composerContent.canRecognizeSpeech()) add(CodexSheetAction("语音输入", CodexGlyph.EditNote,
                        onClick = {
                            vm.composerContent.beginVoice(draftOwner, selectedWorkspace)?.let { intent ->
                                try { voicePicker.launch(intent) }
                                catch (failure: Exception) {
                                    vm.composerContent.finishVoice(null)
                                    vm.composerContent.pickerFailure(failure)
                                }
                            }
                        }, description = "使用系统语音识别，结果写入草稿，不自动发送", testTag = "composer-add-voice"))
                })
            if (composerMenu == "tools") CodexActionSheet(title = "任务配置", onDismiss = { composerMenu = "" },
                actions = tools.filter { it.command !in setOf("model", "mode") }.map {
                    CodexSheetAction(it.title, it.icon, it.onOpen, description = it.detail,
                        enabled = it.enabled, testTag = "composer-tool-" + it.command)
                })
        }
    }
    if (composerMenu == "reference") ComposerFileReferenceSheet(vm, selectedWorkspace, referenceQuery.orEmpty(),
        onSelect = { vm.composerContent.addReference(draftOwner, it, referenceQuery); composerMenu = ""; referenceQuery = null },
        onDismiss = { composerMenu = ""; referenceQuery = null })
    if (composerMenu == "skills") ComposerSkillSheet(vm, selectedWorkspace,
        onSelect = { vm.composerContent.addReference(draftOwner, it); composerMenu = "" }, onDismiss = { composerMenu = "" })
    if (composerMenu == "projects") ComposerWorkspaceSheet(vm, selectedWorkspace, onRegistry = { composerMenu = "project-registry" }, onDismiss = { composerMenu = "" })
    if (composerMenu == "project-registry") ProjectRegistryPanel(vm, java.io.File(selectedWorkspace), onDismiss = { composerMenu = "" })
    if (composerMenu == "usage") ComposerContextUsageSheet(thread?.let { models["tokenUsage:$it"] },
        onCompact = if (!busy && !switching && thread != null) ({ composerMenu = ""; sessionAction = "compact" }) else null,
        onDismiss = { composerMenu = "" })
    if (showTerminalSheet) {
        CodexSheetFrame(
            onDismissRequest = { showTerminalSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Ink.canvas
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("项目终端", style = MaterialTheme.typography.titleMedium)
                        Text(PresentationPolicy.workspaceName(selectedWorkspace),
                            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    }
                    CodexIconAction(onClick = { showTerminalSheet = false }) {
                        CodexIcon(CodexGlyph.Close, "收起终端")
                    }
                }
                Box(Modifier.weight(1f)) { TerminalPage(vm, embedded = true) }
            }
        }
    }
    if (showContextTools) {
        ThreadContextTools(
            vm = vm,
            navigate = { destination ->
                pendingContextRoute = destination
                showContextTools = false
            },
            onDismiss = { showContextTools = false },
            onFork = { showContextTools = false; sessionAction = "fork" },
            onCompact = { showContextTools = false; sessionAction = "compact" }
        )
    }
    if (sessionAction.isNotBlank() && thread != null) {
        val id = thread
        val fork = sessionAction == "fork"
        CodexConfirmDialog(
            title = if (fork) "创建会话分支" else "整理上下文",
            message = if (fork) "保留当前会话，从其历史创建独立会话。"
                else "内核将整理较早的上下文，部分细节可能需要重新读取。",
            confirmLabel = if (fork) "创建并打开" else "开始整理",
            onConfirm = {
                sessionAction = ""
                id?.let { vm.runSessionAction(it, fork) }
            },
            onDismiss = { sessionAction = "" }
        )
    }
    if (confirmPlan && latestPlan != null) {
        CodexConfirmDialog(
            title = "确认按计划执行",
            message = "请核对上方方案，确认后会准备官方执行指令并切换到执行模式。",
            detail = latestPlan.text.take(600),
            confirmLabel = "开始按方案执行",
            onConfirm = {
                confirmPlan = false
                vm.approvePlan(latestPlan.text)
            },
            onDismiss = { confirmPlan = false }
        )
    }
    if (showApprovalQuick) ApprovalQuickSheet(vm) { showApprovalQuick = false }
    if (showModels) ModelChoiceSurface(vm) { showModels = false }
    if (showModes) ModeChoiceSurface(vm) { showModes = false }
    if (showEngineStatus) EngineStatusSheet(
        vm, onDismiss = { showEngineStatus = false }, navigate = navigate
    )
}

