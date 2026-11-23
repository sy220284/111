package io.github.sy220284.codexandroid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class Page(
    val id: String,
    val title: String,
    val section: String,
    val subtitle: String,
    val icon: ImageVector,
    val rpc: String? = null,
    val experimental: Boolean = false
)

val pages = listOf(
    Page("home", "总览", "工作台", "任务、连接与待决事项", Icons.Outlined.Home),
    Page("chat", "任务对话", "工作台", "会话、流式生成与输入", Icons.Outlined.Chat),
    Page("threads", "会话历史", "工作台", "继续、检索和归档任务", Icons.Outlined.History, "thread/list"),
    Page("approvals", "待审批操作", "工作台", "待确认命令、修改与权限", Icons.Outlined.GppGood),
    Page("search", "全文检索", "工作台", "定位历史会话", Icons.Outlined.Search, "thread/search"),
    Page("goals", "任务目标", "工作台", "线程目标与上下文", Icons.Outlined.Flag, "thread/goal/get"),
    Page("queue", "任务队列", "工作台", "待处理步骤与顺序", Icons.Outlined.ViewList, "thread/queue/list", true),
    Page("files", "工作区文件", "项目与代码", "导入、打开和编辑工程文件", Icons.Outlined.FolderOpen),
    Page("diff", "代码差异", "项目与代码", "当前任务的改动追踪", Icons.Outlined.Code),
    Page("terminal", "终端", "项目与代码", "在设备沙箱中执行命令", Icons.Outlined.Terminal),
    Page("review", "代码审查", "项目与代码", "发起 Codex 审查", Icons.Outlined.FactCheck, "review/start"),
    Page("models", "模型目录", "能力与扩展", "可用模型与思考能力", Icons.Outlined.AutoAwesome, "model/list"),
    Page("modes", "协作模式", "能力与扩展", "计划、编码与协作模式", Icons.Outlined.PeopleOutline, "collaborationMode/list"),
    Page("skills", "技能", "能力与扩展", "发现并管理技能", Icons.Outlined.School, "skills/list"),
    Page("plugins", "插件", "能力与扩展", "安装状态与能力", Icons.Outlined.Extension, "plugin/list"),
    Page("apps", "连接应用", "能力与扩展", "应用连接与权限", Icons.Outlined.Apps, "app/list"),
    Page("mcp", "MCP 服务", "能力与扩展", "已连接的可用服务", Icons.Outlined.Hub, "mcpServerStatus/list"),
    Page("environments", "执行环境", "能力与扩展", "设备和远端环境", Icons.Outlined.Dns, "environment/status", true),
    Page("account", "账号登录", "设置与账户", "ChatGPT 登录与会话", Icons.Outlined.AccountCircle, "account/read"),
    Page("providers", "模型服务商", "设置与账户", "供应商及默认项", Icons.Outlined.Tune, "modelProvider/list"),
    Page("config", "引擎配置", "设置与账户", "逐项读取和写入官方配置", Icons.Outlined.Settings, "config/read"),
    Page("security", "权限与安全", "设置与账户", "审批、沙箱和网络策略", Icons.Outlined.Security, "configRequirements/read"),
    Page("usage", "用量与额度", "设置与账户", "调用统计、额度窗口与重置时间", Icons.Outlined.Insights, "account/usage/read"),
    Page("limits", "使用限额", "设置与账户", "限额和重置时间", Icons.Outlined.Speed, "account/rateLimits/read"),
    Page("appearance", "外观与交互", "设置与账户", "显示密度、材质和动效", Icons.Outlined.Palette),
    Page("diagnostics", "诊断与日志", "设置与账户", "本机连接与运行状态", Icons.Outlined.BugReport, "server/diagnostics", true),
    Page("about", "关于项目", "设置与账户", "版本、源码与隐私", Icons.Outlined.Info),
    Page("settings", "设置", "通用", "账户、功能、主机与外观", Icons.Outlined.Settings)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CodexShell(vm: CodexViewModel) {
    var selected by rememberSaveable { mutableStateOf("home") }
    var backHistory by rememberSaveable { mutableStateOf(listOf("home")) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val sidebarState = rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val pageStateHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val pending by vm.bridge.prompts.collectAsState()
    val terminalSession by vm.terminalSession.collectAsState()
    val dataTransfer by AppDataTransfer.state.collectAsState()
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    val operationSnacks = remember { SnackbarHostState() }
    val app = LocalContext.current.applicationContext as CodexApplication
    val pendingDeepLink by app.pendingDeepLink.collectAsState()
    LaunchedEffect(vm) {
        vm.actionFeedback.collect { receipt ->
            operationSnacks.showSnackbar(
                message = receipt.message,
                withDismissAction = receipt.isError,
                duration = if (receipt.isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }
    val page = pages.firstOrNull { it.id == selected } ?: pages.first()
    val newThreadBlockReason = NavigationPolicy.newThreadBlockReason(
        firstThreadStarting, switching
    )
    var showStatus by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var showGoal by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var showThreadLibrary by remember { mutableStateOf(false) }
    var showApprovals by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var libraryInitialFullText by remember { mutableStateOf(false) }
    // Carry the original action across a successful thread selection.
    var pendingThreadAction by remember { mutableStateOf<String?>(null) }
    var pendingResumeRoute by remember { mutableStateOf<String?>(null) }
    val move: (String) -> Unit = move@{ destination ->
        if (UiFeatureCatalog.requiresThread(destination) && activeThread == null) {
            pendingThreadAction = destination
            libraryInitialFullText = false
            showThreadLibrary = true
            scope.launch { drawer.close() }
            return@move
        }
        when (destination) {
            "threads", "search" -> {
                pendingThreadAction = null
                libraryInitialFullText = destination == "search"
                showThreadLibrary = true
                scope.launch { drawer.close() }
                return@move
            }
            "queue" -> {
                showQueue = true
                scope.launch { drawer.close() }
                return@move
            }
            "models" -> {
                showModels = true
                scope.launch { drawer.close() }
                return@move
            }
            "about" -> {
                showAbout = true
                scope.launch { drawer.close() }
                return@move
            }
            "approvals" -> {
                showApprovals = true
                scope.launch { drawer.close() }
                return@move
            }
            "review" -> {
                showReview = true
                scope.launch { drawer.close() }
                return@move
            }
            "goals" -> {
                showGoal = true
                scope.launch { drawer.close() }
                return@move
            }
            "modes" -> {
                showMode = true
                scope.launch { drawer.close() }
                return@move
            }
            "history-tools", "thread-attachments" -> {
                if (activeThread == null) {
                    pendingThreadAction = destination
                    libraryInitialFullText = false
                    showThreadLibrary = true
                } else if (destination == "history-tools") showHistory = true
                else showAttachments = true
                scope.launch { drawer.close() }
                return@move
            }
        }
        if (page.id != destination) {
            backHistory = (backHistory + destination).takeLast(18)
            // Each specialized screen owns its initial read and refresh lifecycle.
            // Only these three pages intentionally delegate an entry refresh to the shell.
            when (destination) {
                "account" -> vm.refresh("account", "account/read")
                "config" -> vm.refresh("config", "config/read",
                    org.json.JSONObject().put("includeLayers", true))
                "diagnostics" -> vm.refresh("diagnostics", "server/diagnostics")
            }
        }
        selected = destination
        focusManager.clearFocus()
        keyboard?.hide()
        scope.launch { drawer.close() }
    }
    // An older saved route may still point to a screen now owned by an overlay.
    LaunchedEffect(Unit) {
        val restored = selected
        // A previous build could save sheet destinations as full navigation entries.
        // Strip them from the back stack before either restoring or navigating.
        val history = NavigationPolicy.pageHistory(backHistory)
        when {
            UiFeatureCatalog.isSheet(restored) -> {
                selected = history.lastOrNull() ?: "home"
                backHistory = history.ifEmpty { listOf("home") }
                move(restored)
            }
            UiFeatureCatalog.byRoute[restored]?.surface !in
                setOf(FeatureSurface.Page, FeatureSurface.Reader) -> {
                selected = "settings"
                backHistory = (history + "settings").takeLast(18)
            }
            else -> {
                backHistory = (if (history.lastOrNull() == restored) history
                    else history + restored).takeLast(18)
            }
        }
    }
    LaunchedEffect(pendingResumeRoute, showThreadLibrary, activeThread) {
        val route = pendingResumeRoute
        if (route != null && !showThreadLibrary && activeThread != null) {
            pendingResumeRoute = null
            move(route)
        }
    }
    LaunchedEffect(pendingDeepLink, status) {
        val id = pendingDeepLink
        if (id != null && status == EngineStatus.Connected) {
            app.pendingDeepLink.value = null
            vm.chooseThread(id) { move("chat") }
        }
    }

    val goBack: () -> Unit = {
        if (backHistory.size > 1) {
            backHistory = backHistory.dropLast(1)
            selected = backHistory.last()
            focusManager.clearFocus()
            keyboard?.hide()
        } else {
            move("settings")
        }
    }
    BackHandler(enabled = drawer.currentValue == DrawerValue.Closed && backHistory.size > 1) {
        goBack()
    }

    Box(Modifier.fillMaxSize().background(Ink.canvas)) {
        val content: @Composable () -> Unit = {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(operationSnacks) },
                topBar = {
                    if (selected == "chat" || selected == "home") {
                        ChatWorkTopBar(
                            selected = selected,
                            newThreadBlockReason = newThreadBlockReason,
                            onMenu = { scope.launch { drawer.open() } },
                            onNavigate = move,
                            onNewThread = { vm.newThread(); move("chat") }
                        )
                    } else TopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AnimatedContent(
                                    targetState = page.title,
                                    transitionSpec = {
                                        fadeIn(tween(CodexMotion.duration(180, reduceMotion))) togetherWith
                                            fadeOut(tween(CodexMotion.duration(100, reduceMotion)))
                                    }, label = "page-title"
                                ) { label ->
                                    Text(label, modifier = Modifier.testTag("screen-title"), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium)
                                }
                                // 顶栏保留当前页面，正文只呈现一次功能介绍。
                            }
                        },
                        navigationIcon = {
                            val isNested = NavigationPolicy.isDetailPage(selected)
                            IconButton(
                                onClick = { if (isNested) goBack() else scope.launch { drawer.open() } },
                                modifier = Modifier.padding(start = 6.dp).size(45.dp)
                                    .border(1.dp, Ink.border, CircleShape)
                            ) {
                                Icon(if (isNested) Icons.Outlined.ArrowBack else Icons.Outlined.Menu,
                                    if (isNested) "返回上一页" else "打开侧边导航")
                            }
                        },
                        actions = {
                            if (selected == "files" &&
                                backHistory.dropLast(1).lastOrNull() == "chat") {
                                IconButton(
                                    onClick = goBack,
                                    modifier = Modifier.testTag("files-return-to-conversation")
                                ) {
                                    Icon(Icons.Outlined.ArrowBack,
                                        contentDescription = "返回上一页")
                                }
                            }
                            if (pending.isNotEmpty()) {
                                BadgedBox(badge = { Badge(containerColor = Ink.canvas, contentColor = Ink.red,
                                        modifier = Modifier.border(1.dp, Ink.red, RoundedCornerShape(99.dp))) {
                                        Text(pending.size.toString())
                                    } }) {
                                    IconButton(onClick = { move("approvals") }) { Icon(Icons.Outlined.GppGood, "待审批") }
                                }
                            }
                            IconButton(onClick = { showStatus = true },
                                modifier = Modifier.testTag("engine-status-open")) {
                                Icon(
                                    if (status == EngineStatus.Connected) Icons.Outlined.CheckCircle
                                    else Icons.Outlined.SyncProblem,
                                    contentDescription = "查看运行状态",
                                    tint = if (status == EngineStatus.Connected) Ink.jade else Ink.warning
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.canvas)
                    )
                }
            ) { inset ->
                Box(Modifier.padding(inset).fillMaxSize()) {
                    // Only the current page participates in semantics and input.
                    // This prevents stale search fields and buttons from remaining
                    // interactive during rapid settings / feature navigation.
                    pageStateHolder.SaveableStateProvider(selected) {
                        CompositionLocalProvider(LocalWorkPageTitle provides page.title) {
                            PageContent(selected, vm, move)
                        }
                    }
                }
            }
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 318.dp)
                        .then(if (drawer.isOpen) Modifier else Modifier.clearAndSetSemantics {}),
                    drawerContainerColor = Ink.recessed,
                    drawerContentColor = Ink.parchment
                ) { Sidebar(Modifier.fillMaxHeight(), selected,
                    { id ->
                        if (id == "status") {
                            showStatus = true
                            scope.launch { drawer.close() }
                        } else move(id)
                    }, status, pending.size, newThreadBlockReason,
                    { vm.newThread(); move("chat") }, vm,
                    { id -> vm.chooseThread(id) { move("chat") } },
                    sidebarState) }
            }
        ) { content() }

        if (showStatus) {
            EngineStatusSheet(vm, onDismiss = { showStatus = false }, navigate = move)
        }
        if (showReview) pageStateHolder.SaveableStateProvider("sheet:review:${activeThread}") {
            ReviewScopeSheet(vm, onDismiss = { showReview = false }, navigate = move)
        }
        if (showGoal) pageStateHolder.SaveableStateProvider("sheet:goal:${activeThread}") {
            ThreadGoalSheet(vm, onDismiss = { showGoal = false })
        }
        if (showMode) ModeChoiceSurface(vm) { showMode = false }
        if (showHistory) ThreadHistorySheet(vm) { showHistory = false }
        if (showAttachments) ThreadAttachmentsSheet(vm) { showAttachments = false }
        if (showThreadLibrary) pageStateHolder.SaveableStateProvider(
            "sheet:library:${libraryInitialFullText}"
        ) {
            ThreadLibraryQuickSheet(vm,
                initialFullText = libraryInitialFullText,
                continuationLabel = pendingThreadAction?.let { requested ->
                    pages.firstOrNull { it.id == requested }?.title ?: "当前操作"
                },
                onDismiss = {
                    showThreadLibrary = false
                    pendingThreadAction = null
                    pendingResumeRoute = null
                },
                onThreadSelected = { pendingResumeRoute = pendingThreadAction },
                navigate = { destination ->
                    showThreadLibrary = false
                    pendingThreadAction = null
                    move(destination)
                })
        }
        if (showApprovals) pageStateHolder.SaveableStateProvider("sheet:approvals") {
            ApprovalQuickSheet(vm) { showApprovals = false }
        }
        if (showQueue) pageStateHolder.SaveableStateProvider("sheet:queue:${activeThread}") {
            ThreadQueueQuickSheet(vm) { showQueue = false }
        }
        if (showModels) ModelChoiceSurface(vm) { showModels = false }
        if (showAbout) AboutInfoSheet(vm) { showAbout = false }
        // Archive transfer must block all foreground pages and sheets.
        DataTransferBusyOverlay(dataTransfer)
    }
}

/** 功能常驻、会话独立滚动，保持底部新会话和账号入口固定。 */
@Composable
private fun Sidebar(
    modifier: Modifier, selected: String, onSelect: (String) -> Unit,
    status: EngineStatus, approvals: Int, newThreadBlockReason: String?, onNewThread: () -> Unit,
    vm: CodexViewModel, onOpenThread: (String) -> Unit,
    sidebarState: SidebarUiState
) {
    val activeWorkspace by vm.activeWorkspace.collectAsState()
    Column(
        modifier.background(Ink.canvas).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Codex", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall, color = Ink.parchment)
            IconButton(onClick = { onSelect("search") },
                modifier = Modifier.size(48.dp).testTag("sidebar-global-search")) {
                Icon(Icons.Outlined.Search, "搜索全部会话", tint = Ink.parchment)
            }
        }
        Button(
            onClick = onNewThread,
            enabled = newThreadBlockReason == null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                .heightIn(min = 52.dp).testTag("sidebar-new-chat")
                .explainDisabled(newThreadBlockReason != null, newThreadBlockReason.orEmpty()),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink.coral, contentColor = Color.White,
                disabledContainerColor = Ink.border, disabledContentColor = Ink.muted)
        ) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("新建任务", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
        }
        listOf("chat", "home", "files").forEach { id ->
            SidebarRoute(id, selected, approvals, onSelect)
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                .testTag("sidebar-project-context")
                .clickable { onSelect("files") },
            shape = RoundedCornerShape(14.dp),
            color = Ink.elevated
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前项目", style = MaterialTheme.typography.labelSmall,
                        color = Ink.muted)
                    Text(PresentationPolicy.workspaceName(activeWorkspace),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 7.dp),
            color = Ink.border.copy(alpha = .65f))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SidebarSessions(vm, onOpenThread, onViewAll = { onSelect("threads") },
                modifier = Modifier.fillMaxSize(), uiState = sidebarState)
        }
        HorizontalDivider(color = Ink.border.copy(alpha = .65f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("账号与设置", style = MaterialTheme.typography.labelMedium,
                color = Ink.muted, modifier = Modifier.padding(start = 13.dp))
            Row {
                IconButton(onClick = { onSelect("account") },
                    modifier = Modifier.size(48.dp).testTag("nav-account")) {
                    Icon(Icons.Outlined.AccountCircle, "账号", tint = Ink.parchment)
                }
                IconButton(onClick = { onSelect("settings") },
                    modifier = Modifier.size(48.dp).testTag("nav-settings")) {
                    Icon(Icons.Outlined.Settings, "设置", tint = Ink.parchment)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .clickable {
                    if (status is EngineStatus.Unavailable) vm.connect()
                    else onSelect("status")
                }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.RadioButtonChecked, null,
                tint = if (status == EngineStatus.Connected) Ink.jade else Ink.warning,
                modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
            Text(when (status) {
                EngineStatus.Connected -> "内核已连接"
                EngineStatus.Starting -> "正在连接本机内核"
                is EngineStatus.Unavailable -> "连接已断开 · 点击重连"
            },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted,
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SidebarRoute(
    id: String, selected: String, approvals: Int, onSelect: (String) -> Unit
) {
    val page = pages.first { it.id == id }
    val active = selected == id || (id == "settings" && NavigationPolicy.isSettingsChild(selected))
    NavigationDrawerItem(
        label = {
            Text(
                when (id) {
                    "home" -> "工作"
                    "chat" -> "聊天"
                    "threads" -> "会话历史"
                    "files" -> "项目文件"
                    "approvals" -> "待审批"
                    else -> page.title
                },
                style = MaterialTheme.typography.bodyLarge
            )
        },
        selected = active,
        onClick = { onSelect(id) },
        icon = { Icon(page.icon, null, modifier = Modifier.size(21.dp)) },
        badge = {
            if (id == "approvals" && approvals > 0)
                StatusPill(approvals.toString(), tone = StatusTone.Danger)
        },
        modifier = Modifier.testTag("nav-$id").heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Ink.coralSoft.copy(alpha = .12f),
            selectedIconColor = Ink.coral,
            selectedTextColor = Ink.parchment,
            unselectedContainerColor = Ink.canvas,
            unselectedIconColor = Ink.parchment,
            unselectedTextColor = Ink.parchment
        )
    )
}
