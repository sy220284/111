package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
        "apps" -> ConnectedAppsPage(vm)
        "mcp" -> McpServicesPage(vm)
        "environments" -> EnvironmentsPage(vm)
        "diagnostics" -> EngineDiagnosticsPage(vm)
        "usage", "limits" -> AccountMetricsPage(id, vm, navigate)
        "account" -> AccountPage(vm, navigate)
        "config" -> ConfigPage(vm)
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
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 11.dp else 16.dp),
        content = content
    )
}

@Composable
private fun HomePage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val running by vm.running.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val prompts by vm.bridge.prompts.collectAsState()
    val data by vm.data.collectAsState()
    val threads = records(data["threads"])
    val hasAccount = data["account"]?.optJSONObject("account") != null
    var showStatus by rememberSaveable { mutableStateOf(false) }

    ScreenColumn {
        SectionIntro("WORKSPACE / LIVE", "继续你的工程",
            "最近任务、工作文件和需要你确认的事项，集中在这里。")
        if (prompts.isNotEmpty()) {
            CodexNotice("有 ${prompts.size} 项操作正在等待批准", NoticeTone.Warning,
                actionLabel = "处理审批", onAction = { navigate("approvals") })
        }
        if (status != EngineStatus.Connected) {
            CodexNotice("本机 Codex 内核尚未连接", NoticeTone.Info,
                actionLabel = "重新连接", onAction = { vm.connect() })
        } else if (data["account"] != null && !hasAccount) {
            CodexNotice("尚未登录 ChatGPT，可登录账号或配置模型服务商。",
                NoticeTone.Info, actionLabel = "账号登录",
                onAction = { navigate("account") })
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(15.dp), color = Ink.copper.copy(alpha = .17f)) {
                    Icon(if (running) Icons.Outlined.PlayCircleOutline else Icons.Outlined.AutoAwesome,
                        null, tint = Ink.copper, modifier = Modifier.padding(13.dp).size(25.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (running) "返回正在执行的任务" else "开始新任务",
                        style = MaterialTheme.typography.titleLarge)
                    Text(if (running) "查看实时步骤和待处理事项" else "描述需求，Codex 将在本机工作区执行",
                        color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                if (!running) vm.newThread()
                navigate("chat")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(13.dp)) {
                Icon(if (running) Icons.Outlined.PlayArrow else Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "继续当前任务" else "新建 Codex 会话")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("继续最近工作", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { navigate("threads") }) { Text("全部会话") }
        }
        if (threads.isEmpty()) {
            CodexEmptyState(
                "还没有可恢复的会话",
                "完成第一个任务后，这里会展示最近工作。历史记录由本机 Codex 内核读取。",
                actionLabel = "打开会话",
                onAction = { navigate("threads") }
            )
        } else {
            threads.take(3).forEach { item ->
                ThreadRow(item, enabled = !running ||
                    vm.activeThread.value == item.optString("id")) {
                    val id = item.optString("id")
                    if (!running) vm.chooseThread(id)
                    navigate("chat")
                }
            }
        }
        Text("工作区快捷操作", style = MaterialTheme.typography.titleLarge)
        CodexActionTile("工作区文件", "导入、查看或编辑工程文件",
            Icons.Outlined.FolderOpen, onClick = { navigate("files") },
            modifier = Modifier.fillMaxWidth())
        CodexActionTile("代码差异", "检查本轮改动并发起审查",
            Icons.Outlined.Difference, onClick = { navigate("diff") },
            modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { showStatus = !showStatus },
            modifier = Modifier.fillMaxWidth()) {
            Icon(if (showStatus) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Spacer(Modifier.width(7.dp))
            Text(if (showStatus) "收起运行状态" else "查看运行状态与统计")
        }
        CodexReveal(showStatus) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallStat("本机内核",
                        if (status == EngineStatus.Connected) "已连接" else "待连接",
                        Icons.Outlined.Memory, Modifier.weight(1f))
                    SmallStat("待审批", prompts.size.toString(),
                        Icons.Outlined.GppGood, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallStat("当前已读取会话", threads.size.toString(),
                        Icons.Outlined.History, Modifier.weight(1f))
                    SmallStat("工作目录", "本地存储",
                        Icons.Outlined.Folder, Modifier.weight(1f))
                }
                OutlinedButton(onClick = { navigate("diagnostics") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                    Text("查看引擎诊断")
                }
            }
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
private fun ThreadRow(item: JSONObject, enabled: Boolean, onClick: () -> Unit) {
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
                    item.optString("name").ifBlank { item.optString("preview").ifBlank { "未命名会话" } },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(item.optString("cwd").ifBlank { item.optString("id") }, color = Ink.muted,
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
    val activeTurn by vm.activeTurn.collectAsState()
    val steering by vm.steerBusy.collectAsState()
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
    val attachmentContext = LocalContext.current
    var attachedImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var attachmentBusy by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf("") }

    val selectedMode by vm.selectedMode.collectAsState()
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (thread == null) "新的工作会话" else "进行中的任务", style = MaterialTheme.typography.titleMedium)
                Text(thread?.take(18) ?: "请选择模型并输入任务", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { vm.newThread() }, enabled = !busy) { Text("新建") }
            IconButton(onClick = { showContextTools = true }, enabled = thread != null && !busy) {
                Icon(Icons.Outlined.MoreHoriz, "会话工具")
            }
            IconButton(onClick = { navigate("diff") }) { Icon(Icons.Outlined.Difference, "查看代码差异") }
        }
        DividerLine()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { navigate("threads") },
                label = { Text("会话历史") },
                leadingIcon = { Icon(Icons.Outlined.History, null, modifier = Modifier.size(17.dp)) },
                modifier = Modifier.heightIn(min = 46.dp)
            )
            if (latestDiff.isNotBlank()) {
                AssistChip(
                    onClick = { navigate("diff") },
                    label = { Text("查看最新改动") },
                    leadingIcon = { Icon(Icons.Outlined.Difference, null, modifier = Modifier.size(17.dp)) },
                    modifier = Modifier.heightIn(min = 46.dp)
                )
            }
        }
        CodexReveal(visible = prompts.isNotEmpty()) {
            CodexNotice("本次任务有 ${prompts.size} 项待审批操作",
                NoticeTone.Warning, Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                actionLabel = "查看", onAction = { navigate("approvals") })
        }
        CodexReveal(visible = busy) {
            CodexNotice("任务执行中 · 已显示 ${items.size} 条步骤记录",
                NoticeTone.Info, Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (items.isEmpty()) {
                val shortViewport = maxHeight < 210.dp
                Column(
                    modifier = Modifier.align(Alignment.Center)
                        .padding(horizontal = 18.dp, vertical = if (shortViewport) 6.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Ink.copper,
                        modifier = Modifier.size(if (shortViewport) 26.dp else 38.dp))
                    Spacer(Modifier.height(if (shortViewport) 8.dp else 16.dp))
                    Text("从一个具体目标开始",
                        style = if (shortViewport) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                        maxLines = 2, textAlign = TextAlign.Center)
                    if (!shortViewport) {
                        Spacer(Modifier.height(6.dp))
                        Text("Codex 将按会话、步骤与工具执行呈现过程。",
                            style = MaterialTheme.typography.bodyMedium, color = Ink.muted,
                            textAlign = TextAlign.Center)
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
                    color = Ink.copper,
                    shadowElevation = 4.dp
                ) {
                    Text("↓ ${unreadSteps} 条新步骤",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Ink.canvas, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        errors["conversation"]?.let { ErrorText(it) }
        errors["steer"]?.let { ErrorText(it) }
        DividerLine()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            ConversationControlBar(
                modelName = vm.modelId.collectAsState().value,
                mode = selectedMode,
                connected = status == EngineStatus.Connected,
                running = busy,
                importingImage = attachmentBusy,
                imageCount = attachedImages.size,
                onModel = { showModels = true },
                onMode = { showModes = true },
                onDiagnostics = { navigate("diagnostics") },
                onImage = { imagePicker.launch("image/*") }
            )
            if (!busy && status != EngineStatus.Connected) {
                Text("内核未连接，点击右侧状态图标查看诊断",
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
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                placeholder = { Text(if (busy) "任务运行中，可以补充要求或修正方向…" else "描述目标、文件及验收要求…") },
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.interrupt() },
                    enabled = busy,
                    modifier = Modifier.weight(.38f).heightIn(min = 50.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red),
                    contentPadding = PaddingValues(horizontal = 9.dp)
                ) {
                    Icon(Icons.Outlined.StopCircle, null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("停止")
                }
                Button(
                    onClick = {
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
                    },
                    enabled = status == EngineStatus.Connected && !attachmentBusy &&
                        (if (busy) draft.isNotBlank() && thread != null &&
                            !activeTurn.isNullOrBlank() && !steering
                         else draft.isNotBlank() || attachedImages.isNotEmpty()),
                    modifier = Modifier.weight(.62f).heightIn(min = 50.dp)
                ) {
                    Icon(if (busy) Icons.Outlined.EditNote else Icons.Outlined.ArrowUpward, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (busy) "补充指令" else "发送给 Codex", maxLines = 1)
                }
            }
        }
    }
    if (showContextTools) {
        ThreadContextTools(vm = vm, navigate = navigate, onDismiss = { showContextTools = false })
    }
    if (showModels) {
        val available = records(models["models"]).filter { !it.optBoolean("hidden") }.take(80)
        CodexChoiceSheet(
            title = "选择运行模型", description = "影响后续任务，已经启动的任务继续使用原设置。",
            choices = if (available.isEmpty()) listOf(
                CodexChoice("__reload", "刷新模型目录", "读取 Codex 内核支持的模型")
            ) else available.map {
                val id = it.optString("id").ifBlank { it.optString("model") }
                CodexChoice(id, it.optString("displayName").ifBlank { id }, it.optString("description"))
            }.distinctBy { it.id },
            selectedId = vm.modelId.collectAsState().value,
            onSelect = { id ->
                if (id == "__reload") vm.refresh("models", "model/list") else vm.modelId.value = id
                showModels = false
            },
            onDismiss = { showModels = false }
        )
    }
    if (showModes) {
        CodexChoiceSheet(
            title = "选择协作方式", description = "直接执行或先规划步骤；运行中不能切换。",
            choices = listOf(
                CodexChoice("default", "直接执行", "按任务要求执行并报告进度"),
                CodexChoice("plan", "先制定计划", "先明确步骤，再由你决定下一步")
            ),
            selectedId = selectedMode,
            onSelect = { vm.selectedMode.value = it; showModes = false },
            onDismiss = { showModes = false }
        )
    }
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
    val title = when (item.kind) {
        "user" -> "你的指令"
        "agentMessage" -> "Codex 回复"
        "reasoning", "reasoningSummaryTextDelta" -> "推理与分析"
        "plan" -> "任务计划"
        "commandExecution" -> "命令执行"
        "fileChange" -> "文件变更"
        "mcpToolCall" -> "外部工具调用"
        "error" -> "执行异常"
        else -> item.kind
    }
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
        color = if (user) Ink.copper.copy(alpha = .12f) else Ink.panel,
        border = BorderStroke(1.dp, if (failure) Ink.red.copy(alpha = .5f) else
            if (user) Ink.copperDim else Ink.border.copy(alpha = .65f))
    ) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 13.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = tone.copy(alpha = .14f), shape = RoundedCornerShape(12.dp)) {
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
                    if (item.status.isNotBlank()) {
                        Text(
                            when (item.status.lowercase()) {
                                "completed" -> "已完成"
                                "inprogress", "in_progress", "running", "streaming" -> "执行中"
                                "failed" -> "失败"
                                "declined" -> "已拒绝"
                                else -> item.status
                            },
                            style = MaterialTheme.typography.labelMedium, color = tone)
                    }
                }
                Text(item.text.ifBlank { "等待执行内容…" },
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
    Surface(color = Ink.red.copy(alpha = .12f), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.red.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.red)
            Spacer(Modifier.width(8.dp))
            Text(error, color = Ink.red, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CatalogPage(page: Page, vm: CodexViewModel) {
    val result by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val payload = result[page.id]
    val entries = records(payload)
    var expanded by remember { mutableStateOf(false) }
    var operation by remember(page.id) { mutableStateOf(page.rpc ?: "") }
    var arguments by remember(page.id) { mutableStateOf("{}") }
    LaunchedEffect(page.id) { page.rpc?.let { vm.refresh(page.id, it) } }
    ScreenColumn {
        SectionIntro("CODEX / " + page.section, page.title, page.subtitle)
        if (page.experimental) StatusPill("上游实验接口", false)
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(page.icon, null, tint = Ink.copper)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("实时服务数据", style = MaterialTheme.typography.titleMedium)
                    Text(page.rpc ?: "本地功能", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { page.rpc?.let { vm.refresh(page.id, it) } }) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
            }
            if (page.id in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[page.id]?.let { ErrorText(it) }
        }
        if (entries.isNotEmpty()) entries.forEachIndexed { idx, entry ->
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.optString("displayName")
                            .ifBlank { entry.optString("name") }
                            .ifBlank { entry.optString("title") }
                            .ifBlank { entry.optString("id") }
                            .ifBlank { "记录 " + (idx + 1) },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                    )
                    entry.optString("status").takeIf { it.isNotBlank() }?.let { StatusPill(it) }
                }
                Text(entry.optString("description").ifBlank { entry.optString("summary") },
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                var showDetail by remember { mutableStateOf(false) }
                TextButton(onClick = { showDetail = !showDetail }) { Text(if (showDetail) "收起详情" else "查看详情") }
                CodexReveal(showDetail) {
                    Text(entry.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        } else if (payload != null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("服务返回", style = MaterialTheme.typography.titleMedium)
                Text(payload.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else if (page.id !in loading && page.id !in errors) {
            MaterialPanel(Modifier.fillMaxWidth()) { Text("从运行核心读取数据后将在此显示。", color = Ink.muted) }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Icon(Icons.Outlined.Terminal, null); Spacer(Modifier.width(7.dp)); Text("高级协议操作")
        }
        CodexReveal(expanded) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("调用官方 App Server 接口", style = MaterialTheme.typography.titleMedium)
                Text("请求参数采用 JSON，异常会显示在本页。修改或删除操作需明确输入对应方法。", color = Ink.muted)
                OutlinedTextField(operation, { operation = it }, label = { Text("method") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(arguments, { arguments = it }, label = { Text("params JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    try { vm.rpc(page.id, operation.trim(), JSONObject(arguments)) }
                    catch (failure: Exception) { vm.errors.value = vm.errors.value + (page.id to (failure.message ?: "JSON 格式不正确")) }
                }, enabled = operation.isNotBlank()) { Text("发送请求") }
            }
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
            Button(onClick = { vm.refresh(id, method, JSONObject().put("threadId", active)) }) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("读取当前会话")
            }
            errors[id]?.let { ErrorText(it) }
            payload[id]?.let { MaterialPanel(Modifier.fillMaxWidth()) { Text(it.toString(2), fontFamily = FontFamily.Monospace) } }
        }
    }
}

@Composable
private fun ThreadSearchPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val running by vm.running.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    ScreenColumn {
        SectionIntro("SEARCH / THREADS", "全文检索", "通过 Codex 的历史检索定位旧任务。")
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("搜索词") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Button(onClick = { vm.refresh("search", "thread/search", JSONObject().put("searchTerm", query)) }, enabled = query.isNotBlank()) { Text("搜索") }
        errors["search"]?.let { ErrorText(it) }
        records(data["search"]).forEach { entry ->
            ThreadRow(entry, enabled = ThreadDisplayPolicy.canOpenThread(
                running, activeThread, entry.optString("id"))) {
                if (!running) vm.chooseThread(entry.optString("id"))
                navigate("chat")
            }
        }
    }
}
