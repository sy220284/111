package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
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

@Composable
internal fun ConversationPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val status by vm.bridge.status.collectAsState()
    val thread by vm.activeThread.collectAsState()
    val busy by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeTurn by vm.activeTurn.collectAsState()
    val steering by vm.steerBusy.collectAsState()
    val loading by vm.loading.collectAsState()
    val items by vm.timeline.collectAsState()
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
    var showAttachmentTools by remember { mutableStateOf(false) }
    var pendingContextRoute by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentRoute by remember { mutableStateOf<String?>(null) }
    var pendingImagePick by remember { mutableStateOf(false) }
    var imagePickThread by remember { mutableStateOf<String?>(null) }
    var showEngineStatus by remember { mutableStateOf(false) }
    var showApprovalQuick by remember { mutableStateOf(false) }
    var stopRequested by remember(activeTurn) { mutableStateOf(false) }
    val stopping = busy && (("interrupt" in loading) ||
        (stopRequested && errors["interrupt"] == null))
    val attachmentContext = LocalContext.current
    var attachmentBusy by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf("") }

    val selectedMode by vm.selectedMode.collectAsState()
    val currentTitle = thread?.let { threadId ->
        (records(models["sidebarThreads"]) + records(models["threads"]))
            .firstOrNull { it.optString("id") == threadId }?.let {
            PresentationPolicy.threadTitle(it.optString("name"), it.optString("preview"))
        }
    }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(LocalImageAttachments.MAX_FILES)
    ) { selected ->
        if (selected.isNotEmpty()) {
            // The selection belongs to the thread that opened the picker.
            val owner = imagePickThread
            attachmentBusy = true
            scope.launch {
                val initial = vm.composerDrafts.value[ThreadComposerDrafts.key(owner)]
                    ?.images.orEmpty()
                val room = (LocalImageAttachments.MAX_FILES - initial.size).coerceAtLeast(0)
                var added = 0
                val failures = mutableListOf<String>()
                selected.take(room).forEach { uri ->
                    try {
                        val path = LocalImageAttachments.importFrom(attachmentContext, uri)
                        val current = vm.composerDrafts.value[ThreadComposerDrafts.key(owner)]
                            ?.images.orEmpty()
                        if (current.size < LocalImageAttachments.MAX_FILES) {
                            vm.editComposerImages(owner, current + path)
                            added++
                        } else {
                            LocalImageAttachments.release(attachmentContext, path)
                        }
                    } catch (failure: Exception) {
                        failures += failure.message ?: "图片导入失败"
                    }
                }
                val skipped = (selected.size - room).coerceAtLeast(0)
                attachmentError = when {
                    failures.isNotEmpty() -> "已添加${added}张；${failures.distinct().joinToString("；")}"
                    skipped > 0 -> "已添加${added}张，另有${skipped}张超过本次剩余名额"
                    else -> ""
                }
                attachmentBusy = false
            }
        }
    }
    // Page-owned handoff: old ModalBottomSheet has left composition before next page opens.
    LaunchedEffect(showContextTools, pendingContextRoute) {
        if (!showContextTools) {
            pendingContextRoute?.let { destination ->
                pendingContextRoute = null
                navigate(destination)
            }
        }
    }
    LaunchedEffect(showAttachmentTools, pendingAttachmentRoute, pendingImagePick) {
        if (!showAttachmentTools) {
            pendingAttachmentRoute?.let { destination ->
                pendingAttachmentRoute = null
                navigate(destination)
            }
            if (pendingImagePick) {
                pendingImagePick = false
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
    var followTail by remember { mutableStateOf(true) }
    var lastKnownStepCount by remember { mutableIntStateOf(0) }
    var unreadSteps by remember { mutableIntStateOf(0) }
    // 会话恢复后的时间线从当前会话末尾开始，不沿用上一会话的未读数和滚动位置。
    LaunchedEffect(thread) {
        followTail = true
        unreadSteps = 0
        lastKnownStepCount = items.size
        if (items.isNotEmpty()) scroll.scrollToItem(items.lastIndex)
        else scroll.scrollToItem(0)
    }
    LaunchedEffect(scroll) {
        snapshotFlow {
            val total = scroll.layoutInfo.totalItemsCount
            val tail = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total <= 1 || tail >= total - 2
        }.collect { atBottom ->
            followTail = atBottom
            if (atBottom) unreadSteps = 0
        }
    }
    LaunchedEffect(items.size) {
        val added = (items.size - lastKnownStepCount).coerceAtLeast(0)
        lastKnownStepCount = items.size
        if (items.isNotEmpty() && followTail) {
            if (reducedMotion) scroll.scrollToItem(items.size - 1)
            else scroll.animateScrollToItem(items.size - 1)
        } else if (!followTail) {
            unreadSteps += added
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (thread != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (thread == null) "新的工作会话" else currentTitle ?: "当前任务",
                    style = MaterialTheme.typography.titleMedium)
                Text("本机工作区",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { vm.newThread() }, enabled = !busy) { Text("新建") }
            IconButton(onClick = { showContextTools = true }, enabled = thread != null && !busy) {
                Icon(Icons.Outlined.MoreHoriz, "会话工具")
            }
            IconButton(onClick = { navigate("diff") }, enabled = thread != null) { Icon(Icons.Outlined.Difference, "查看代码差异") }
        }
        DividerLine()
        }
        // 会话导航统一归入侧边栏，正文只保留与当前任务有关的快捷动作。
        if (latestDiff.isNotBlank()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { navigate("diff") },
                label = { Text("查看最新改动") },
                leadingIcon = { Icon(Icons.Outlined.Difference, null, modifier = Modifier.size(17.dp)) },
                modifier = Modifier.heightIn(min = 46.dp)
            )
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
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (switching) {
                ConversationLoadingSkeleton(Modifier.fillMaxSize())
            } else if (items.isEmpty()) {
                val shortViewport = maxHeight < 345.dp
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                        .padding(horizontal = 19.dp, vertical = if (shortViewport) 5.dp else 15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (shortViewport) 6.dp else 10.dp)
                ) {
                    Text("今天想做什么？",
                        style = if (shortViewport) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.headlineSmall,
                        color = Ink.parchment, textAlign = TextAlign.Center)
                    Text("描述一个目标，或从快捷任务开始",
                        color = Ink.muted, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center)
                    if (!shortViewport && !busy) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = { navigate("files") },
                                label = { Text("浏览文件") },
                                leadingIcon = { Icon(Icons.Outlined.FolderOpen, null,
                                    modifier = Modifier.size(18.dp)) }
                            )
                            AssistChip(
                                onClick = { vm.editComposerText(thread, "帮我写一个脚本，目标是：") },
                                label = { Text("写个脚本") },
                                leadingIcon = { Icon(Icons.Outlined.Code, null,
                                    modifier = Modifier.size(18.dp)) }
                            )
                            AssistChip(
                                onClick = { vm.editComposerText(thread, "请协助初始化一个新项目，需求是：") },
                                label = { Text("新建项目") },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null,
                                    modifier = Modifier.size(18.dp)) }
                            )
                            AssistChip(
                                onClick = { navigate(if (thread == null) "threads" else "review") },
                                label = { Text("代码审查") },
                                leadingIcon = { Icon(Icons.Outlined.FactCheck, null,
                                    modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                }
            } else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    if (reducedMotion) scroll.scrollToItem(items.lastIndex)
                                    else scroll.animateScrollToItem(items.lastIndex)
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
        errors["conversation"]?.let { reason ->
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
                        vm.sendPrompt(originalText, originalImages) {
                            vm.clearComposerAfterAccepted(vm.activeThread.value,
                                originalText, originalImages)
                        }
                    }
                }
            )
        }
        errors["steer"]?.let { ErrorText(it) }
        errors["interrupt"]?.let { ErrorText(it) }
        DividerLine()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            ConversationControlBar(
                modelName = vm.modelId.collectAsState().value,
                mode = selectedMode,
                connected = status == EngineStatus.Connected,
                running = busy,
                onModel = { showModels = true },
                onMode = { showModes = true },
                onDiagnostics = { showEngineStatus = true }
            )
            if (status is EngineStatus.Unavailable) {
                CodexNotice(
                    "运行核心连接中断，未发送文字已保留在本机。",
                    NoticeTone.Warning, actionLabel = "重新连接",
                    onAction = { vm.connect() }
                )
            } else if (status == EngineStatus.Starting) {
                Text("正在连接本机运行核心…",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            errors["drafts"]?.let {
                CodexNotice(it, NoticeTone.Error)
            }
            if (attachmentBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在导入图片…", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            if (attachmentError.isNotBlank()) {
                ErrorText(attachmentError)
            }
            errors["attachments"]?.let { ErrorText(it) }
            if (busy && attachedImages.isNotEmpty()) {
                Text("运行中仅支持补充文字；已选图片将在下一轮发送。",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            LocalImageAttachmentStrip(attachedImages) { path ->
                vm.editComposerImages(thread, attachedImages - path)
                scope.launch {
                    try {
                        LocalImageAttachments.release(attachmentContext, path)
                    } catch (failure: Exception) {
                        attachmentError = failure.message ?: "无法移除图片"
                    }
                }
            }
            ConversationComposer(
                draft = draft,
                onDraftChange = { vm.editComposerText(thread, it) },
                attachedCount = attachedImages.size,
                connected = status == EngineStatus.Connected,
                running = busy,
                stopping = stopping,
                canStop = busy && !activeTurn.isNullOrBlank(),
                canSend = status == EngineStatus.Connected && !switching && !attachmentBusy &&
                    (if (busy) draft.isNotBlank() && thread != null &&
                        !activeTurn.isNullOrBlank() && !steering
                     else draft.isNotBlank() || attachedImages.isNotEmpty()),
                sending = steering || (busy && activeTurn.isNullOrBlank()),
                onAttach = { showAttachmentTools = true },
                onStop = { stopRequested = true; vm.interrupt() },
                onSend = {
                    val message = draft.trim()
                    val submittedDraft = draft
                    if (busy) {
                        vm.steerPrompt(message) {
                            vm.clearComposerAfterAccepted(thread, submittedDraft, emptyList())
                        }
                    } else {
                        val images = attachedImages.toList()
                        vm.sendPrompt(message, images) {
                            // 首条消息创建真实会话后，草稿已迁移到新 threadId。
                            vm.clearComposerAfterAccepted(vm.activeThread.value, submittedDraft, images)
                        }
                    }
                },
                menuExpanded = showAttachmentTools,
                hasThread = thread != null,
                imageImporting = attachmentBusy,
                onMenuDismiss = { showAttachmentTools = false },
                onMenuRoute = { destination ->
                    pendingAttachmentRoute = destination
                    showAttachmentTools = false
                },
                onPickImage = {
                    imagePickThread = thread
                    pendingImagePick = true
                    showAttachmentTools = false
                }
            )
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
    if (showApprovalQuick) ApprovalQuickSheet(vm) { showApprovalQuick = false }
    if (showModels) ModelChoiceSurface(vm) { showModels = false }
    if (showModes) ModeChoiceSurface(vm) { showModes = false }
    if (showEngineStatus) EngineStatusSheet(
        vm, onDismiss = { showEngineStatus = false }, navigate = navigate
    )
}

/** Every tool step retains the source payload for verification while keeping the chat readable. */
@Composable
private fun TimelineStepCard(item: TimelineItem) {
    val user = item.kind == "user"
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = Ink.canvas,
        border = BorderStroke(1.dp, if (failure) Ink.red.copy(alpha = .5f) else
            if (user) Ink.copperDim else Ink.border.copy(alpha = .65f))
    ) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 13.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = Ink.canvas,
                    border = BorderStroke(1.dp, tone.copy(alpha = .32f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(icon, null, tint = tone, modifier = Modifier.padding(10.dp).size(18.dp))
                }
                if (!user) {
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(1.dp).height(20.dp).background(Ink.border))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    val statusLabel = PresentationPolicy.timelineStatus(item.status)
                    if (statusLabel.isNotEmpty()) {
                        Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = tone)
                    }
                }
                Text(item.text.takeUnless { it == item.kind || PresentationPolicy.meaningful(it).isEmpty() }
                    ?: "正在处理…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (item.kind == "commandExecution") FontFamily.Monospace else null)
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
    if (evidenceOpen) CodexEvidenceSheet(
        title = title + " · 执行依据",
        payload = item.evidence,
        onDismiss = { evidenceOpen = false }
    )
}
