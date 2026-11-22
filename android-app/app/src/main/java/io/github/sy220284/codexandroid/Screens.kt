package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun PageContent(id: String, vm: CodexViewModel, navigate: (String) -> Unit) {
    when (id) {
        "home" -> HomePage(vm, navigate)
        "settings" -> SettingsHubPage(vm, navigate)
        "chat" -> ConversationPage(vm, navigate)
        "threads" -> ThreadLibraryPage(vm, navigate)
        "files" -> WorkspacePage(vm)
        "diff" -> DiffPage(vm, navigate)
        "terminal" -> TerminalPage(vm)
        "review" -> ReviewPage(vm, navigate)
        "approvals" -> ApprovalsPage(vm)
        "models" -> ModelsPage(vm)
        "providers" -> ProvidersPage(vm)
        "skills" -> SkillsPage(vm)
        "plugins" -> PluginsPage(vm)
        "hooks" -> HooksPage(vm)
        "modes" -> CollaborationModesPage(vm)
        "apps" -> ConnectedAppsPage(vm, navigate)
        "mcp" -> McpServicesPage(vm, navigate)
        "environments" -> EnvironmentsPage(vm)
        "diagnostics" -> EngineDiagnosticsPage(vm)
        "usage", "limits" -> AccountMetricsPage(id, vm, navigate)
        "account" -> AccountPage(vm, navigate)
        "config" -> ConfigPage(vm, navigate)
        "appearance" -> AppearancePage()
        "security" -> SecurityPage(vm, navigate)
        "about" -> AboutPage(vm)
        "search" -> ThreadSearchPage(vm, navigate)
        "goals" -> ThreadGoalPage(vm)
        "queue" -> ThreadQueuePage(vm)
        else -> CatalogPage(pages.first { it.id == id }, vm)
    }
}

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val compact by UiPreferences.compact.collectAsState()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 12.dp else 18.dp,
                    vertical = if (compact) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 11.dp else 16.dp),
            content = content
        )
    }
}

@Composable
private fun HomePage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val prompts by vm.bridge.prompts.collectAsState()
    val data by vm.data.collectAsState()
    val threads = records(data["threads"])
    val hasAccount = data["account"]?.optJSONObject("account") != null

    ScreenColumn {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (running) "继续当前工作" else "从一项任务开始",
                style = MaterialTheme.typography.headlineSmall)
            Text("描述目标，或从现有工作区继续。",
                style = MaterialTheme.typography.bodyLarge, color = Ink.muted)
            if (status == EngineStatus.Connected) {
                StatusPill("本机引擎已连接", tone = StatusTone.Success)
            }
        }
        if (prompts.isNotEmpty()) CodexNotice(
            "有 " + prompts.size + " 项操作等待审批", NoticeTone.Warning,
            actionLabel = "去处理", onAction = { navigate("approvals") }
        )
        if (status != EngineStatus.Connected) {
            CodexNotice("内核尚未连接，可以前往设置检查连接。", NoticeTone.Info,
                actionLabel = "查看诊断", onAction = { navigate("diagnostics") })
        } else if (data["account"] != null && !hasAccount) {
            CodexNotice("尚未登录，可在设置中完成账号或服务商配置。",
                NoticeTone.Info, actionLabel = "去登录", onAction = { navigate("account") })
        }
        CodexPrimaryButton(
            onClick = { if (!running) vm.newThread(); navigate("chat") },
            enabled = !switching,
            busy = switching,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(if (running) Icons.Outlined.PlayArrow else Icons.Outlined.Add, null)
            Spacer(Modifier.width(9.dp))
            Text(if (running) "继续当前任务" else "开启新对话")
        }
        Text("从这里开始", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 5.dp, top = 11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WelcomeActionCard(
                title = "查看工作区", subtitle = "浏览文件与项目",
                icon = Icons.Outlined.FolderOpen, tone = Ink.jade,
                onClick = { navigate("files") }, modifier = Modifier.weight(1f)
            )
            WelcomeActionCard(
                title = "写个小脚本", subtitle = "在对话中描述目标",
                icon = Icons.Outlined.Code, tone = Ink.copper,
                onClick = { navigate("chat") }, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WelcomeActionCard(
                title = "检查修改", subtitle = "查看本轮代码差异",
                icon = Icons.Outlined.Difference, tone = Ink.copper,
                onClick = { navigate("diff") }, modifier = Modifier.weight(1f)
            )
            WelcomeActionCard(
                title = "运行命令", subtitle = "打开工作区终端",
                icon = Icons.Outlined.Terminal, tone = Ink.jade,
                onClick = { navigate("terminal") }, modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("最近工作", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { navigate("threads") }) { Text("全部会话") }
        }
        if (threads.isEmpty() && data["threads"] != null) {
            Text("完成首个任务后，最近的会话会出现在这里。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        } else if (threads.isEmpty()) {
            Text("等待连接并同步最近的会话。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            threads.take(2).forEach { item ->
                ThreadRow(item, enabled = !switching && (!running ||
                    vm.activeThread.value == item.optString("id"))) {
                    val id = item.optString("id")
                    if (!running) vm.chooseThread(id)
                    navigate("chat")
                }
            }
        }
        OutlinedButton(
            onClick = { navigate("settings") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)
        ) {
            Icon(Icons.Outlined.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text("更多功能与设置")
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    MaterialPanel(modifier) {
        Icon(icon, null, tint = Ink.copper)
        Spacer(Modifier.height(16.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun ThreadRow(item: JSONObject, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        color = Ink.panel, border = BorderStroke(1.dp, Ink.border.copy(alpha = .5f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Forum, null, tint = Ink.copper)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    PresentationPolicy.threadTitle(item.optString("name"), item.optString("preview")),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(PresentationPolicy.workspaceName(item.optString("cwd")), color = Ink.muted,
                    maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
            if (!enabled) {
                Text("执行中不可切换", color = Ink.muted,
                    style = MaterialTheme.typography.labelSmall)
            } else Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
        }
    }
}

@Composable
private fun ConversationPage(vm: CodexViewModel, navigate: (String) -> Unit) {
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
    var draft by rememberSaveable { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var showContextTools by remember { mutableStateOf(false) }
    var showAttachmentTools by remember { mutableStateOf(false) }
    var pendingContextRoute by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentRoute by remember { mutableStateOf<String?>(null) }
    var pendingImagePick by remember { mutableStateOf(false) }
    var showEngineStatus by remember { mutableStateOf(false) }
    var showApprovalQuick by remember { mutableStateOf(false) }
    var stopRequested by remember(activeTurn) { mutableStateOf(false) }
    val stopping = busy && (("interrupt" in loading) ||
        (stopRequested && errors["interrupt"] == null))
    val attachmentContext = LocalContext.current
    var attachedImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var attachmentBusy by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf("") }

    val selectedMode by vm.selectedMode.collectAsState()
    val currentTitle = thread?.let { threadId ->
        records(models["threads"]).firstOrNull { it.optString("id") == threadId }?.let {
            PresentationPolicy.threadTitle(it.optString("name"), it.optString("preview"))
        }
    }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (attachedImages.size >= LocalImageAttachments.MAX_FILES) {
                attachmentError = "最多添加三张图片，请先移除一张"
            } else {
                attachmentBusy = true
                scope.launch {
                    try {
                        val path = LocalImageAttachments.importFrom(attachmentContext, uri)
                        attachedImages = attachedImages + path
                        attachmentError = ""
                    } catch (failure: Exception) {
                        attachmentError = failure.message ?: "无法读取所选图片"
                    } finally {
                        attachmentBusy = false
                    }
                }
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
                imagePicker.launch("image/*")
            }
        }
    }
    var followTail by remember { mutableStateOf(true) }
    var lastKnownStepCount by remember { mutableIntStateOf(0) }
    var unreadSteps by remember { mutableIntStateOf(0) }
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
            if (items.isEmpty()) {
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
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WelcomeActionCard(
                                "浏览文件", "打开工作区", Icons.Outlined.FolderOpen,
                                Ink.jade, onClick = { navigate("files") }, modifier = Modifier.weight(1f)
                            )
                            WelcomeActionCard(
                                "写个脚本", "先描述需求", Icons.Outlined.Code,
                                Ink.copper, onClick = { draft = "帮我写一个脚本，目标是：" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WelcomeActionCard(
                                "新建项目", "整理初始化步骤", Icons.Outlined.CreateNewFolder,
                                Ink.copper, onClick = { draft = "请协助初始化一个新项目，需求是：" },
                                modifier = Modifier.weight(1f)
                            )
                            WelcomeActionCard(
                                "代码审查", "检查修改质量", Icons.Outlined.FactCheck,
                                Ink.jade, onClick = { navigate(if (thread == null) "threads" else "review") }, modifier = Modifier.weight(1f)
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
        errors["conversation"]?.let { ErrorText(it) }
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
            if (!busy && status != EngineStatus.Connected) {
                Text("内核未连接，点击右侧状态图标查看运行状态",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            if (attachmentBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在导入图片…", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            if (attachmentError.isNotBlank()) {
                ErrorText(attachmentError)
            }
            errors["attachments"]?.let { ErrorText(it) }
            LocalImageAttachmentStrip(attachedImages) { path ->
                attachedImages = attachedImages - path
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
                onDraftChange = { draft = it },
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
                    if (busy) {
                        vm.steerPrompt(message) {
                            if (draft.trim() == message) draft = ""
                        }
                    } else {
                        val images = attachedImages.toList()
                        vm.sendPrompt(message, images) {
                            if (draft.trim() == message) draft = ""
                            if (attachedImages == images) attachedImages = emptyList()
                        }
                    }
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
    if (showAttachmentTools) {
        WorkspaceActionsSheet(
            onDismiss = { showAttachmentTools = false },
            navigate = { destination ->
                pendingAttachmentRoute = destination
                showAttachmentTools = false
            }, hasThread = thread != null,
            imageCount = attachedImages.size, importing = attachmentBusy,
            onPickImage = {
                pendingImagePick = true
                showAttachmentTools = false
            }
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

/** On phones, evidence lives in a bottom sheet instead of squeezing the chat timeline. */
/** Different upstream responses use data, threads, or a module-specific array. */
fun records(response: JSONObject?): List<JSONObject> {
    if (response == null) return emptyList()
    val keys = listOf("data", "threads", "skills", "models", "plugins", "apps", "items",
        "hooks", "servers", "environments", "providers", "modes", "results", "accounts")
    for (key in keys) {
        val array = response.optJSONArray(key)
        if (array != null) return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    return emptyList()
}

@Composable
fun ErrorText(error: String) {
    Surface(color = Ink.canvas, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.red.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.red)
            Spacer(Modifier.width(8.dp))
            Text(PresentationPolicy.readableError(error), color = Ink.red,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CatalogPage(page: Page, vm: CodexViewModel) {
    val result by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    val payload = result[page.id]
    val entries = records(payload)
    LaunchedEffect(page.id) { page.rpc?.let { vm.refresh(page.id, it) } }
    ScreenColumn {
        SectionIntro("", page.title, page.subtitle)
        if (page.experimental) StatusPill("实验功能", tone = StatusTone.Neutral)
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(page.icon, null, tint = Ink.copper)
                Spacer(Modifier.width(12.dp))
                Text("当前状态", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                CodexRefreshIcon(busy = page.id in loading,
                    onRefresh = { page.rpc?.let { vm.refresh(page.id, it) } },
                    label = "刷新${page.title}")
            }
            if (page.id in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[page.id]?.let { ErrorText(it) }
        }
        if (entries.isNotEmpty()) {
            entries.forEachIndexed { index, entry ->
                val display = sequenceOf("displayName", "name", "title").map {
                    PresentationPolicy.meaningful(entry.optString(it))
                }.firstOrNull { it.isNotEmpty() } ?: "记录 ${index + 1}"
                val summary = PresentationPolicy.meaningful(entry.optString("description"))
                    .ifEmpty { PresentationPolicy.meaningful(entry.optString("summary")) }
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text(display, style = MaterialTheme.typography.titleMedium)
                    val status = PresentationPolicy.timelineStatus(entry.optString("status"))
                    if (status.isNotEmpty()) StatusPill(status)
                    if (summary.isNotEmpty()) {
                        Text(summary, color = Ink.muted,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else if (payload != null && page.id !in loading && page.id !in errors) {
            CodexEmptyState("暂无可展示内容", "已完成读取，当前没有需要处理的项目。")
        } else if (page.id !in loading && page.id !in errors) {
            CodexEmptyState(
                title = if (engineStatus == EngineStatus.Connected) "等待读取结果" else "运行核心尚未连接",
                description = if (engineStatus == EngineStatus.Connected)
                    "当前没有收到数据，可以重新加载。" else "连接本机内核后才能读取当前功能。",
                actionLabel = if (engineStatus == EngineStatus.Connected) "重新加载" else "重新连接",
                onAction = {
                    if (engineStatus == EngineStatus.Connected) {
                        page.rpc?.let { vm.refresh(page.id, it) }
                    } else vm.connect()
                }
            )
        }
    }
}

@Composable
fun ThreadScopedPage(id: String, vm: CodexViewModel) {
    val active by vm.activeThread.collectAsState()
    val page = pages.first { it.id == id }
    val errors by vm.errors.collectAsState()
    val payload by vm.data.collectAsState()
    val method = page.rpc ?: return
    ScreenColumn {
        SectionIntro("THREAD / CONTEXT", page.title, page.subtitle)
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("需要先打开一个会话", style = MaterialTheme.typography.titleMedium)
                Text("从会话历史或任务对话中选择当前任务。", color = Ink.muted)
            }
        } else {
            OutlinedButton(onClick = { vm.refresh(id, method, JSONObject().put("threadId", active)) }) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("读取当前会话")
            }
            errors[id]?.let { ErrorText(it) }
            payload[id]?.let { response ->
                val rows = records(response)
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text(if (rows.isEmpty()) "当前任务没有更多可显示内容"
                        else "已读取 ${rows.size} 条任务记录",
                        style = MaterialTheme.typography.bodyMedium)
                    rows.take(10).forEach { entry ->
                        val description = PresentationPolicy.meaningful(entry.optString("title"))
                            .ifEmpty { PresentationPolicy.meaningful(entry.optString("text")) }
                        if (description.isNotEmpty()) Text(description)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadSearchPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val loading by vm.loading.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    ScreenColumn {
        SectionIntro("SEARCH / THREADS", "全文检索", "通过 Codex 的历史检索定位旧任务。")
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("搜索词") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        CodexPrimaryButton(
            onClick = { vm.refresh("search", "thread/search",
                JSONObject().put("searchTerm", query.trim())) },
            enabled = query.isNotBlank() && "search" !in loading,
            busy = "search" in loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("查找历史会话")
        }
        ProtocolFeedback(vm, listOf("search"), onRetry = {
            vm.refresh("search", "thread/search",
                JSONObject().put("searchTerm", query.trim()))
        })
        if (data["search"] != null && records(data["search"]).isEmpty() &&
            "search" !in loading && errors["search"] == null) {
            CodexEmptyState("没有找到相关会话",
                "尝试换一个关键词，或返回会话历史查看全部记录。",
                actionLabel = "查看全部会话", onAction = { navigate("threads") })
        }
        records(data["search"]).forEach { entry ->
            ThreadRow(entry, enabled = !switching && ThreadDisplayPolicy.canOpenThread(
                running, activeThread, entry.optString("id"))) {
                if (!running) vm.chooseThread(entry.optString("id"))
                navigate("chat")
            }
        }
    }
}
