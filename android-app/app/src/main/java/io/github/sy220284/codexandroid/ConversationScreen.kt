package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.scrollBy
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ConversationPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val status by vm.bridge.status.collectAsState()
    val thread by vm.activeThread.collectAsState()
    val busy by vm.running.collectAsState()
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeTurn by vm.activeTurn.collectAsState()
    val steering by vm.steerBusy.collectAsState()
    val loading by vm.loading.collectAsState()
    val items by vm.timeline.collectAsState()
    val olderTimelineCount by vm.olderTimelineCount.collectAsState()
    val latestDiff by vm.latestDiff.collectAsState()
    val models by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val prompts by vm.bridge.prompts.collectAsState()
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    val composerDrafts by vm.composerDrafts.collectAsState()
    val snapshot = composerDrafts[ThreadComposerDrafts.key(thread)] ?: ComposerDraft()
    val draft = snapshot.text
    val attachedImages = snapshot.images
    var showModels by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var showContextTools by remember { mutableStateOf(false) }
    var showTerminalSheet by remember { mutableStateOf(false) }
    var followUpMode by rememberSaveable(thread) { mutableStateOf("queue") }
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
        imported.threadKey == ThreadComposerDrafts.key(thread)
    val attachmentError = if (imported.threadKey == ThreadComposerDrafts.key(thread))
        imported.message else ""

    // 手机竖屏输入法打开时收起次要控件，优先保留输入和发送区。
    val compactWorkArea = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val selectedMode by vm.selectedMode.collectAsState()
    val selectedWorkspace by vm.activeWorkspace.collectAsState()
    val goal = thread?.let { models["goal:$it"]?.optJSONObject("goal") }
    val queuedInputs = thread?.let { records(models["queue:$it"]) }.orEmpty()
    val changedFiles = remember(latestDiff) { UnifiedDiff.parse(latestDiff) }
    LaunchedEffect(thread) {
        if (thread != null) {
            vm.refresh("goal:$thread", "thread/goal/get", JSONObject().put("threadId", thread))
            vm.refresh("queue:$thread", "thread/queue/list",
                JSONObject().put("threadId", thread).put("limit", 100))
        }
    }
    val planApprovalBusy by vm.planApprovalBusy.collectAsState()
    var confirmPlan by remember { mutableStateOf(false) }
    val lastPlanIndex = items.indexOfLast {
        it.kind == "plan" && it.text.isNotBlank() &&
            !it.status.contains("stream", ignoreCase = true)
    }
    val latestPlan = items.getOrNull(lastPlanIndex)
    val planHasLaterUserInput = lastPlanIndex >= 0 &&
        items.drop(lastPlanIndex + 1).any { it.kind == "user" }
    val offerPlanApproval = latestPlan != null && !planHasLaterUserInput &&
        selectedMode == "plan" && !busy && !switching && thread != null
    val workspaceLabel = if (selectedWorkspace == vm.workingDirectory) "本机工作区"
        else File(selectedWorkspace).name.ifBlank { "本机工作区" }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun startFromSuggestion(prompt: String) {
        if (draft.isNotBlank()) return
        vm.editComposerText(thread, prompt)
        composerFocus.requestFocus()
        keyboard?.show()
    }
    val tailIndex = if (items.isEmpty()) 0 else items.lastIndex +
        if (olderTimelineCount > 0 && thread != null) 1 else 0
    suspend fun scrollToLatest() {
        scroll.scrollToItem(tailIndex)
        // A long last message may occupy more than one viewport.
        val lastSize = scroll.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == tailIndex }?.size ?: 0
        if (lastSize > 0) scroll.scrollBy(lastSize.toFloat())
    }
    // Pickers use the actual remaining quota, so no selected image silently vanishes.
    val oneImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.importPickedImages(listOfNotNull(uri)) }
    val twoImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { selected -> vm.importPickedImages(selected) }
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
    var followTail by remember { mutableStateOf(true) }
    var lastKnownStepCount by remember { mutableIntStateOf(0) }
    var lastKnownTailId by remember { mutableStateOf<String?>(null) }
    var lastKnownTailText by remember { mutableStateOf<String?>(null) }
    var autoScrolling by remember { mutableStateOf(false) }
    var unreadSteps by remember { mutableIntStateOf(0) }
    var observedThread by remember { mutableStateOf<String?>(null) }
    // Reset only after thread/read and thread/resume have finished swapping the timeline.
    LaunchedEffect(thread, switching) {
        if (!switching && observedThread != thread) {
            observedThread = thread
            followTail = true
            unreadSteps = 0
            lastKnownStepCount = items.size
            lastKnownTailId = items.lastOrNull()?.id
            lastKnownTailText = items.lastOrNull()?.text
            if (items.isNotEmpty()) {
                autoScrolling = true
                try { scrollToLatest() } finally { autoScrolling = false }
            }
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to !scroll.canScrollForward }.collect { (scrolling, atBottom) ->
            if (atBottom) {
                followTail = true
                unreadSteps = 0
            } else if (scrolling && !autoScrolling) followTail = false
        }
    }
    LaunchedEffect(items.size, items.lastOrNull()?.text, switching, thread, olderTimelineCount) {
        if (switching || observedThread != thread) return@LaunchedEffect
        // Reading older pages prepends items but does not create unread steps.
        val tailId = items.lastOrNull()?.id
        val tailChanged = tailId != lastKnownTailId
        val added = if (tailChanged) (items.size - lastKnownStepCount).coerceAtLeast(0) else 0
        val tailText = items.lastOrNull()?.text
        val tailTextChanged = tailText != lastKnownTailText
        lastKnownTailText = tailText
        lastKnownTailId = tailId
        lastKnownStepCount = items.size
        if (items.isNotEmpty() && followTail && (tailChanged || tailTextChanged)) {
            autoScrolling = true
            try { scrollToLatest() } finally { autoScrolling = false }
        } else if (!followTail) {
            unreadSteps += added
        }
    }
    // 窗口已配置 adjustResize，键盘展开时由系统压缩工作区。
    // 不再次叠加 IME 底部内边距，防止输入栏和发送按钮整体被推离可见区域。
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp,
            vertical = if (compactWorkArea) 0.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("chat-workspace-context"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(workspaceLabel, modifier = Modifier.weight(1f, fill = false),
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { navigate("files") },
                modifier = Modifier.testTag("chat-project-files")) {
                Icon(Icons.Outlined.FolderOpen, "打开项目文件", tint = Ink.parchment)
            }
            IconButton(onClick = { showTerminalSheet = true },
                modifier = Modifier.testTag("chat-project-terminal")) {
                Icon(Icons.Outlined.Terminal, "展开项目终端", tint = Ink.parchment)
            }
            if (thread != null) {
                IconButton(onClick = { showContextTools = true }, enabled = !switching) {
                    Icon(Icons.Outlined.MoreHoriz, "会话选项", tint = Ink.parchment)
                }
            }
        }
        HorizontalDivider(color = Ink.border.copy(alpha = .5f))
        if (goal != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
                    .testTag("chat-goal-banner").clickable { navigate("goals") },
                color = Ink.elevated,
                shape = RoundedCornerShape(15.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("任务目标 · " + PresentationPolicy.goalStatus(goal.optString("status")),
                            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                        Text(goal.optString("objective"), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
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
                    Icon(Icons.Outlined.Difference, null, tint = Ink.copper)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("代码变更", style = MaterialTheme.typography.titleSmall)
                        Text("${changedFiles.size} 个文件 · +${changedFiles.sumOf { it.additions }} / −${changedFiles.sumOf { it.deletions }}",
                            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    }
                    TextButton(onClick = { navigate("diff") }) { Text("查看") }
                    TextButton(onClick = { navigate("review") }, enabled = !busy) {
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
                CodexNotice(
                    if (stopping) "已请求停止任务，正在等待内核确认。"
                    else "执行中 · ${items.size} 步 · " +
                        PresentationPolicy.timelineKind(items.lastOrNull()?.kind.orEmpty()),
                    NoticeTone.Info, Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }
        (if (status == EngineStatus.Connected) errors["protocol"] else null)?.let { reason ->
            CodexNotice(reason, NoticeTone.Warning,
                Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                actionLabel = "查看诊断", onAction = { navigate("diagnostics") })
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (switching) {
                ConversationLoadingSkeleton(Modifier.fillMaxSize())
            } else if (items.isEmpty()) {
                val shortViewport = compactWorkArea || maxHeight < 345.dp
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = if (shortViewport) 6.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (shortViewport) 6.dp else 12.dp)
                ) {
                    if (!compactWorkArea) {
                        if (!shortViewport) {
                            Surface(shape = RoundedCornerShape(20.dp),
                                color = Ink.parchment.copy(alpha = .05f)) {
                                Icon(Icons.Outlined.AutoAwesome, null,
                                    modifier = Modifier.padding(14.dp).size(28.dp),
                                    tint = Ink.parchment)
                            }
                        }
                        Text("今天想做什么？",
                            style = if (shortViewport) MaterialTheme.typography.titleLarge
                                else MaterialTheme.typography.headlineSmall,
                            color = Ink.parchment, textAlign = TextAlign.Center)
                        if (!shortViewport) {
                            Text("提出问题，或交给 Codex 一项任务",
                                color = Ink.muted, style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center)
                        }
                    }
                    if (!busy && !firstThreadStarting && !compactWorkArea && draft.isBlank()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().testTag("chat-suggestions"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChatStarter("浏览文件", Icons.Outlined.FolderOpen,
                                    Modifier.weight(1f).testTag("chat-suggestion-files")) { navigate("files") }
                                ChatStarter("编写脚本", Icons.Outlined.Code,
                                    Modifier.weight(1f).testTag("chat-suggestion-script")) {
                                    startFromSuggestion("帮我写一个脚本，目标是：")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChatStarter("新建项目", Icons.Outlined.CreateNewFolder,
                                    Modifier.weight(1f).testTag("chat-suggestion-project")) {
                                    startFromSuggestion("请协助初始化一个新项目，需求是：")
                                }
                                ChatStarter("代码审查", Icons.Outlined.FactCheck,
                                    Modifier.weight(1f).testTag("chat-suggestion-review")) { navigate("review") }
                            }
                        }
                    }
                }
            } else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (olderTimelineCount > 0 && thread != null) item(key = "timeline-older-pages") {
                    OutlinedButton(
                        onClick = { vm.loadEarlierTimeline() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("timeline-load-older")
                    ) {
                        Text("加载更早记录 · 剩余 $olderTimelineCount 条")
                    }
                }
                items(items, key = { it.id }) { item ->
                    TimelineStepCard(item)
                }
            }
            if (unreadSteps > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clickable {
                            scope.launch {
                                if (items.isNotEmpty()) {
                                    autoScrolling = true
                                    followTail = true
                                    try { scrollToLatest() } finally { autoScrolling = false }
                                }
                                unreadSteps = 0
                            }
                        },
                    shape = RoundedCornerShape(99.dp),
                    color = Ink.canvas,
                    border = BorderStroke(1.dp, Ink.copper),
                    shadowElevation = 1.dp
                ) {
                    Text("↓ ${unreadSteps} 条新步骤",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                }
            }
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
                    !busy && !switching &&
                        (draft.isNotBlank() || attachedImages.isNotEmpty()) -> "重试发送"
                    else -> null
                },
                onAction = {
                    if (status is EngineStatus.Unavailable) vm.connect()
                    else if (!busy && !switching) {
                        val originalText = draft
                        val originalImages = attachedImages.toList()
                        vm.sendPrompt(originalText, originalImages) { acceptedThread ->
                            vm.clearComposerAfterAccepted(acceptedThread,
                                originalText, originalImages)
                        }
                    }
                }
            )
        }
        errors["steer"]?.let { ErrorText(it) }
        errors["interrupt"]?.let { ErrorText(it) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp,
            vertical = if (compactWorkArea) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactWorkArea) 4.dp else 7.dp)) {
            if (status is EngineStatus.Unavailable && !compactWorkArea) {
                CodexNotice(
                    "运行核心连接中断，未发送文字已保留在本机。",
                    NoticeTone.Warning, actionLabel = "重新连接",
                    onAction = { vm.connect() }
                )
            } else if (status == EngineStatus.Starting && !compactWorkArea) {
                Text("正在连接本机运行核心…",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            errors["drafts"]?.let {
                CodexNotice(it, NoticeTone.Error)
            }
            errors["draftRecovery"]?.let {
                CodexNotice(it, NoticeTone.Warning)
            }
            if (attachmentBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在导入图片 ${imported.imported}/${imported.requested}…",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            if (attachmentError.isNotBlank()) {
                ErrorText(attachmentError)
            }
            errors["attachments"]?.let { ErrorText(it) }
            errors["queueWrite"]?.let { ErrorText(it) }
            if (queuedInputs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = Ink.muted,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("等待执行 ${queuedInputs.size} 条后续指令",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f), color = Ink.muted)
                    TextButton(onClick = { navigate("queue") },
                        modifier = Modifier.testTag("chat-queued-followups")) { Text("查看") }
                }
            }
            if (busy && attachedImages.isNotEmpty()) {
                Text("运行中仅支持补充文字；已选图片将在下一轮发送。",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            LocalImageAttachmentStrip(attachedImages) { path ->
                vm.removeComposerImage(thread, path)
            }
            ConversationComposer(
                draft = draft,
                focusRequester = composerFocus,
                controls = if (compactWorkArea) null else {
                    {
                        ConversationControlBar(
                            modelName = vm.modelId.collectAsState().value,
                            mode = selectedMode,
                            connected = status == EngineStatus.Connected,
                            starting = status == EngineStatus.Starting,
                            running = busy || firstThreadStarting || switching,
                            onModel = { showModels = true },
                            onMode = { showModes = true },
                            onDiagnostics = { showEngineStatus = true }
                        )
                    }
                },
                onDraftChange = { vm.editComposerText(thread, it) },
                attachedCount = attachedImages.size,
                connected = status == EngineStatus.Connected,
                running = busy,
                stopping = stopping,
                canStop = busy && !activeTurn.isNullOrBlank(),
                canSend = status == EngineStatus.Connected && !switching && !firstThreadStarting && !attachmentBusy &&
                    (if (busy) draft.isNotBlank() && thread != null &&
                        (followUpMode == "queue" || (!activeTurn.isNullOrBlank() && !steering))
                     else draft.isNotBlank() || attachedImages.isNotEmpty()),
                sending = firstThreadStarting || steering || "queueWrite" in loading ||
                    (busy && activeTurn.isNullOrBlank() && followUpMode == "steer"),
                followUpMode = followUpMode,
                onFollowUpModeChange = { followUpMode = it },
                onStop = { stopRequestedForTurn = activeTurn; vm.interrupt() },
                onSend = {
                    val message = draft.trim()
                    val submittedDraft = draft
                    if (busy && followUpMode == "queue" && thread != null) {
                        val params = JSONObject().put("threadId", thread)
                            .put("clientUserMessageId", java.util.UUID.randomUUID().toString())
                            .put("input", JSONArray().put(
                                JSONObject().put("type", "text").put("text", message)))
                        vm.rpc("queueWrite", "thread/queue/add", params, after = {
                            vm.clearComposerAfterAccepted(thread, submittedDraft, emptyList())
                            vm.refresh("queue:$thread", "thread/queue/list",
                                JSONObject().put("threadId", thread).put("limit", 100))
                        })
                    } else if (busy) {
                        vm.steerPrompt(message) {
                            vm.clearComposerAfterAccepted(thread, submittedDraft, emptyList())
                        }
                    } else {
                        val images = attachedImages.toList()
                        vm.sendPrompt(message, images) { acceptedThread ->
                            // 切换会话之后，回执仍只清理实际被接收会话的草稿。
                            vm.clearComposerAfterAccepted(acceptedThread, submittedDraft, images)
                        }
                    }
                },
                imageImporting = attachmentBusy,
                onPickImage = {
                    if (vm.beginImageSelection(thread)) pendingImagePick = true
                },
                compactControls = compactWorkArea,
                compactCanReconnect = status is EngineStatus.Unavailable,
                compactModelLabel = vm.modelId.collectAsState().value,
                compactModeLabel = selectedMode,
                onModel = { showModels = true },
                onMode = { showModes = true },
                onDiagnostics = { showEngineStatus = true },
                onReconnect = { vm.connect() }
            )
        }
    }
    if (showTerminalSheet) {
        ModalBottomSheet(
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
                    IconButton(onClick = { showTerminalSheet = false }) {
                        Icon(Icons.Outlined.Close, "收起终端")
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
            onDismiss = { showContextTools = false }
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

/** Every tool step retains the source payload for verification while keeping the chat readable. */
@Composable
private fun ChatStarter(label: String, icon: ImageVector, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp), color = Ink.canvas,
        border = BorderStroke(1.dp, Ink.border.copy(alpha = .8f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = Ink.muted)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.parchment,
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TimelineStepCard(item: TimelineItem) {
    val user = item.kind in setOf("user", "userMessage")
    val assistant = item.kind in setOf("agent", "agentMessage")
    val failure = item.kind == "error" ||
        item.status.contains("failed", ignoreCase = true)
    val active = item.status.contains("progress", ignoreCase = true) ||
        item.status.contains("stream", ignoreCase = true)
    val tone = when {
        failure -> Ink.red
        active -> Ink.copper
        user -> Ink.copper
        else -> Ink.jade
    }
    val title = PresentationPolicy.timelineKind(item.kind)
    val icon = when (item.kind) {
        "user" -> Icons.Outlined.PersonOutline
        "commandExecution" -> Icons.Outlined.Terminal
        "fileChange" -> Icons.Outlined.Difference
        "mcpToolCall" -> Icons.Outlined.Hub
        "plan" -> Icons.Outlined.ListAlt
        "error" -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.AutoAwesome
    }
    var evidenceOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
    Surface(
        modifier = if (user) Modifier.fillMaxWidth(.88f).testTag("chat-user-message")
            else Modifier.fillMaxWidth().testTag(if (assistant) "chat-assistant-message" else "chat-execution-step"),
        shape = RoundedCornerShape(17.dp),
        color = if (user) Ink.parchment.copy(alpha = .055f) else Ink.canvas,
        border = when {
            failure -> BorderStroke(1.dp, Ink.red.copy(alpha = .5f))
            user || assistant -> null
            else -> BorderStroke(1.dp, Ink.border.copy(alpha = .65f))
        }
    ) {
        Row(Modifier.padding(horizontal = if (assistant) 2.dp else 12.dp, vertical = 12.dp)) {
            if (!user && !assistant) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        color = Ink.muted, modifier = Modifier.weight(1f))
                    val statusLabel = PresentationPolicy.timelineStatus(item.status)
                    if (statusLabel.isNotEmpty()) {
                        Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = tone)
                    }
                }
                SelectionContainer {
                    Text(item.text.takeUnless { it == item.kind || PresentationPolicy.meaningful(it).isEmpty() }
                        ?: "正在处理…",
                        style = if (user || assistant) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodyMedium,
                        fontFamily = if (item.kind == "commandExecution") FontFamily.Monospace else null)
                }
                if (item.evidence.isNotBlank()) {
                    TextButton(onClick = { evidenceOpen = true },
                        modifier = Modifier.heightIn(min = 46.dp)) {
                        Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("查看执行依据")
                    }
                }
            }
        }
    }
    }
    if (evidenceOpen) CodexEvidenceSheet(
        title = title + " · 执行依据",
        payload = item.evidence,
        onDismiss = { evidenceOpen = false }
    )
}
