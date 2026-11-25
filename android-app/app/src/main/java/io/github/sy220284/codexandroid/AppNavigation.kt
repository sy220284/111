package io.github.sy220284.codexandroid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class Page(
    val id: String,
    val title: String,
    val section: String,
    val subtitle: String,
    val icon: CodexGlyph,
    val rpc: String? = null,
    val experimental: Boolean = false
)

val pages = listOf(
    Page("chat", "任务对话", "工作台", "会话、流式生成与输入", CodexGlyph.Thread),
    Page("threads", "会话历史", "工作台", "继续、检索和归档任务", CodexGlyph.History, "thread/list"),
    Page("approvals", "待审批操作", "工作台", "待确认命令、修改与权限", CodexGlyph.Security),
    Page("search", "全文检索", "工作台", "定位历史会话", CodexGlyph.Search, "thread/search"),
    Page("goals", "任务目标", "工作台", "线程目标与上下文", CodexGlyph.Flag, "thread/goal/get"),
    Page("queue", "任务队列", "工作台", "待处理步骤与顺序", CodexGlyph.ViewList, "thread/queue/list", true),
    Page("files", "工作区文件", "项目与代码", "导入、打开和编辑工程文件", CodexGlyph.Project),
    Page("diff", "代码差异", "项目与代码", "当前任务的改动追踪", CodexGlyph.Code),
    Page("terminal", "终端", "项目与代码", "在设备沙箱中执行命令", CodexGlyph.Terminal),
    Page("review", "代码审查", "项目与代码", "发起 Codex 审查", CodexGlyph.FactCheck, "review/start"),
    Page("models", "模型设置", "设置与账户", "默认模型、思考深度和回复详略", CodexGlyph.AutoAwesome, "model/list"),
    Page("modes", "协作模式", "能力与扩展", "计划、编码与协作模式", CodexGlyph.PeopleOutline, "collaborationMode/list"),
    Page("skills", "技能", "能力与扩展", "发现并管理技能", CodexGlyph.School, "skills/list"),
    Page("plugins", "插件", "能力与扩展", "安装状态与能力", CodexGlyph.Extension, "plugin/list"),
    Page("apps", "连接应用", "能力与扩展", "应用连接与权限", CodexGlyph.Apps, "app/list"),
    Page("mcp", "MCP 服务", "能力与扩展", "已连接的可用服务", CodexGlyph.Hub, "mcpServerStatus/list"),
    Page("environments", "运行管理", "设置与账户", "执行设备、后台资源和终端等待", CodexGlyph.Dns, "environment/status", true),
    Page("account", "账号登录", "设置与账户", "ChatGPT 登录与会话", CodexGlyph.AccountCircle, "account/read"),
    Page("providers", "模型服务商", "设置与账户", "供应商及默认项", CodexGlyph.Tune, "modelProvider/list"),
    Page("features", "功能开关", "设置与账户", "全部内核能力、实验功能和默认状态", CodexGlyph.Tune, "experimentalFeature/list"),
    Page("config", "开发者设置", "设置与账户", "高级配置和运行诊断", CodexGlyph.Settings, "config/read"),
    Page("personalization", "个性化设置", "设置与账户", "全局执行指令与工作偏好", CodexGlyph.AccountCircle, "config/read"),
    Page("context", "上下文管理", "设置与账户", "历史压缩与工具输出保留", CodexGlyph.Thread, "config/read"),
    Page("tools", "联网搜索", "能力与扩展", "搜索方式与可用策略", CodexGlyph.Search, "config/read"),
    Page("security", "执行权限", "任务对话", "对话输入框权限选择", CodexGlyph.Security, "configRequirements/read"),
    Page("usage", "用量与额度", "设置与账户", "调用统计、额度窗口与重置时间", CodexGlyph.Insights, "account/usage/read"),
    Page("limits", "使用限额", "设置与账户", "限额和重置时间", CodexGlyph.Speed, "account/rateLimits/read"),
    Page("appearance", "外观与显示", "设置与账户", "主题、字号、布局、动效与任务显示", CodexGlyph.Palette),
    Page("diagnostics", "诊断与日志", "设置与账户", "本机连接与运行状态", CodexGlyph.BugReport, "server/diagnostics", true),
    Page("about", "关于项目", "设置与账户", "版本、源码与隐私", CodexGlyph.Info),
    Page("settings", "设置", "通用", "账户、功能、主机与外观", CodexGlyph.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexShell(vm: CodexViewModel) {
    var savedRoute by rememberSaveable { mutableStateOf(NavigationPolicy.startRoute) }
    val selected = NavigationPolicy.canonicalRoute(savedRoute)
    var backHistory by rememberSaveable { mutableStateOf(listOf(NavigationPolicy.startRoute)) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val sidebarState = rememberSaveable(saver = SidebarUiState.Saver) { SidebarUiState() }
    val pageStateHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    val libraryData by vm.data.collectEntries("sidebarThreads", "threads")
    val libraryResponse = libraryData["sidebarThreads"] ?: libraryData["threads"]
    val answerVersions by vm.answerVersions.state.collectAsState()
    val conversationTitle = remember(activeThread, libraryResponse, answerVersions) {
        activeThread?.let { id ->
            val rows = records(libraryResponse)
            (rows.firstOrNull { it.optString("id") == answerVersions.root(id) }
                ?: rows.firstOrNull { it.optString("id") == id })
                ?.let { PresentationPolicy.threadTitle(it.optString("name"), it.optString("preview")) }
                ?.takeIf { it.isNotBlank() }
        }
    }
    val status by vm.bridge.status.collectAsState()
    val pendingCount by vm.bridge.prompts.collectProjection { it.size }
    val otherPendingCount by vm.bridge.prompts.collectProjection { all ->
        all.size - all.forThread(activeThread).size
    }
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    fun openDrawer() {
        focusManager.clearFocus()
        keyboard?.hide()
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open()
        }
    }
    fun closeDrawer() {
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
    }
    val operationSnacks = remember { SnackbarHostState() }
    val app = LocalContext.current.applicationContext as CodexApplication
    val pendingDeepLink by app.pendingDeepLink.collectAsState()
    LaunchedEffect(vm) {
        vm.actionFeedback.collect { receipt ->
            presentActionFeedback(operationSnacks, receipt,
                isCurrent = vm.threadOrganization::isCurrent,
                onUndo = vm.threadOrganization::undo)
        }
    }
    val page = pages.firstOrNull { it.id == selected } ?: pages.first { it.id == NavigationPolicy.startRoute }
    val newThreadBlockReason = NavigationPolicy.newThreadBlockReason(
        firstThreadStarting, switching
    )
    var activeSheet by remember { mutableStateOf<String?>(null) }
    var requestedSheet by remember { mutableStateOf<String?>(null) }
    fun openSheet(route: String) {
        focusManager.clearFocus()
        keyboard?.hide()
        if (drawer.isOpen || drawer.targetValue == DrawerValue.Open) {
            requestedSheet = route
            closeDrawer()
        } else {
            requestedSheet = null
            activeSheet = route
        }
    }
    fun closeSheet(route: String) {
        if (activeSheet == route) activeSheet = null
    }
    // Carry the original action across a successful thread selection.
    var pendingThreadAction by remember { mutableStateOf<String?>(null) }
    val move: (String) -> Unit = move@{ requested ->
        val destination = NavigationPolicy.canonicalRoute(requested)
        if (UiFeatureCatalog.requiresThread(destination) && vm.activeThread.value == null) {
            pendingThreadAction = destination
            sidebarState.resetFilter()
            sidebarState.searching = false
            openDrawer()
            return@move
        }
        when (destination) {
            "threads", "search" -> {
                pendingThreadAction = null
                sidebarState.resetFilter()
                sidebarState.searching = destination == "search"
                sidebarState.fullText = destination == "search"
                openDrawer()
                return@move
            }
            "models" -> if (page.id != "settings") {
                openSheet("models")
                return@move
            }
            "about" -> {
                openSheet("about")
                return@move
            }
            "goals" -> {
                openSheet("goals")
                return@move
            }
            "modes" -> {
                openSheet("modes")
                return@move
            }
            "history-tools", "thread-attachments" -> {
                if (vm.activeThread.value == null) {
                    pendingThreadAction = destination
                    sidebarState.resetFilter()
                    sidebarState.searching = false
                    openDrawer()
                    return@move
                } else if (destination == "history-tools") openSheet("history-tools")
                else openSheet("thread-attachments")
                return@move
            }
        }
        if (page.id != destination) {
            backHistory = (backHistory + destination).takeLast(18)
            // Each specialized screen owns its initial read and refresh lifecycle.
            // Only account and diagnostics delegate an entry refresh to the shell.
            when (destination) {
                "account" -> vm.refresh("account", "account/read")
                "diagnostics" -> vm.refresh("diagnostics", "server/diagnostics")
            }
        }
        activeSheet = null
        requestedSheet = null
        savedRoute = destination
        focusManager.clearFocus()
        keyboard?.hide()
        closeDrawer()
    }
    // An older saved route may still point to a screen now owned by an overlay.
    LaunchedEffect(Unit) {
        val restored = selected
        savedRoute = restored
        // A previous build could save sheet destinations as full navigation entries.
        // Strip them from the back stack before either restoring or navigating.
        val history = NavigationPolicy.pageHistory(backHistory)
        when {
            UiFeatureCatalog.isSheet(restored) -> {
                savedRoute = history.lastOrNull() ?: NavigationPolicy.startRoute
                backHistory = history.ifEmpty { listOf(NavigationPolicy.startRoute) }
                move(restored)
            }
            UiFeatureCatalog.byRoute[restored]?.surface !in
                setOf(FeatureSurface.Page, FeatureSurface.Reader) -> {
                savedRoute = "settings"
                backHistory = (history + "settings").takeLast(18)
            }
            else -> {
                backHistory = (if (history.lastOrNull() == restored) history
                    else history + restored).takeLast(18)
            }
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
            savedRoute = backHistory.last()
            focusManager.clearFocus()
            keyboard?.hide()
        } else {
            move("settings")
        }
    }
    val drawerOwnsInput = drawer.isOpen || drawer.targetValue == DrawerValue.Open
    LaunchedEffect(requestedSheet, drawerOwnsInput) {
        if (!drawerOwnsInput) requestedSheet?.let { route ->
            activeSheet = route
            requestedSheet = null
        }
    }
    var drawerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(drawerOwnsInput, status) {
        if (drawerOwnsInput && status == EngineStatus.Connected) vm.sessions.refreshIfStale()
        if (!drawerOwnsInput && drawerWasOpen) {
            pendingThreadAction = null
            sidebarState.searching = false
            sidebarState.resetFilter()
            vm.sessions.search("", false)
        }
        drawerWasOpen = drawerOwnsInput
    }
    // The homepage may open the drawer from any unclaimed horizontal swipe.
    // openDrawer clears focus and hides the IME; child controls retain priority.
    val canSwipeOpenDrawer = !drawerOwnsInput && selected == NavigationPolicy.startRoute
    BackHandler(enabled = drawerOwnsInput || backHistory.size > 1) {
        if (drawerOwnsInput) { pendingThreadAction = null; closeDrawer() } else goBack()
    }

    Box(Modifier.fillMaxSize().background(Ink.canvas)) {
        val content: @Composable () -> Unit = {
            Scaffold(
                modifier = Modifier.testTag("work-shell")
                    .pageHorizontalSwipe(!drawerOwnsInput, canSwipeOpenDrawer, ::openDrawer),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { CodexOperationSnackbarHost(operationSnacks) },
                topBar = {
                    if (selected == NavigationPolicy.startRoute) {
                        ChatWorkTopBar(
                            conversationTitle = conversationTitle,
                            newThreadBlockReason = newThreadBlockReason,
                            otherPendingCount = otherPendingCount,
                            onApprovals = { move("approvals") },
                            onMenu = { openDrawer() },
                            onNewThread = { vm.newThread(); move("chat") }
                        )
                    } else TopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(page.title, modifier = Modifier.testTag("screen-title"), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                // 顶栏保留当前页面，正文只呈现一次功能介绍。
                            }
                        },
                        navigationIcon = {
                            val isNested = NavigationPolicy.isDetailPage(selected)
                            CodexIconAction(
                                onClick = { if (isNested) goBack() else openDrawer() },
                                modifier = Modifier.padding(start = 6.dp).size(48.dp)
                                    .border(1.dp, Ink.border, CircleShape)
                            ) {
                                CodexIcon(if (isNested) CodexGlyph.ArrowBack else CodexGlyph.Sidebar,
                                    if (isNested) "返回上一页" else "打开侧边导航")
                            }
                        },
                        actions = {
                            if (selected == "files" &&
                                backHistory.dropLast(1).lastOrNull() == "chat") {
                                CodexIconAction(
                                    onClick = goBack,
                                    modifier = Modifier.testTag("files-return-to-conversation")
                                ) {
                                    CodexIcon(CodexGlyph.ArrowBack,
                                        contentDescription = "返回上一页")
                                }
                            }
                            if (pendingCount > 0) {
                                BadgedBox(badge = { Badge(containerColor = Ink.canvas, contentColor = Ink.red,
                                        modifier = Modifier.border(1.dp, Ink.red, RoundedCornerShape(99.dp))) {
                                        Text(pendingCount.toString())
                                    } }) {
                                    CodexIconAction(onClick = { move("approvals") }) { CodexIcon(CodexGlyph.Security, "待审批") }
                                }
                            }
                            CodexIconAction(onClick = { openSheet("status") },
                                modifier = Modifier.testTag("engine-status-open")) {
                                CodexIcon(
                                    if (status == EngineStatus.Connected) CodexGlyph.TaskComplete
                                    else CodexGlyph.SyncProblem,
                                    contentDescription = "查看运行状态",
                                    tint = if (status == EngineStatus.Connected) Ink.jade else Ink.warning
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.canvas)
                    )
                }
            ) { inset ->
                Column(Modifier.padding(inset).fillMaxSize()) {
                    RuntimeProtectionNotice(vm)
                    RealtimeVoiceRecoveryNotice(vm)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Only the current page participates in semantics and input.
                        // This prevents stale search fields and buttons from remaining
                        // interactive during rapid settings / feature navigation.
                        CodexRouteEntrance(selected, reduceMotion) {
                            pageStateHolder.SaveableStateProvider(selected) {
                                CompositionLocalProvider(LocalWorkPageTitle provides page.title) {
                                    PageContent(selected, vm, move)
                                }
                            }
                        }
                    }
                }
            }
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            // Closed drawer uses the scoped homepage gesture; the native drawer
            // handles an open panel's swipe-to-close and scrim.
            gesturesEnabled = drawerOwnsInput,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 318.dp)
                        .then(if (drawer.isOpen) Modifier.testTag("sidebar-drawer")
                            else Modifier.clearAndSetSemantics {}),
                    drawerContainerColor = Ink.recessed,
                    drawerContentColor = Ink.parchment
                ) {
                    // Retain drawer geometry while closed, but do not run its list or data collectors.
                    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
                        if (drawerOwnsInput) Sidebar(Modifier.fillMaxHeight(), selected,
                    { id ->
                        if (id == "status") {
                            openSheet("status")
                        } else move(id)
                    }, status, pendingCount, newThreadBlockReason,
                    { vm.newThread(); move("chat") }, vm,
                    { id -> vm.chooseThread(id) {
                        val next = pendingThreadAction
                        pendingThreadAction = null
                        move("chat")
                        if (next != null) move(next)
                    } },
                    sidebarState, pendingThreadAction?.let { target ->
                        pages.firstOrNull { it.id == target }?.title ?: "当前操作"
                    })
                    }
                }
            }
        ) { content() }

        if (activeSheet == "status") {
            EngineStatusSheet(vm, onDismiss = { closeSheet("status") }, navigate = move)
        }
        if (activeSheet == "goals") pageStateHolder.SaveableStateProvider("sheet:goal:${activeThread}") {
            ThreadGoalSheet(vm, onDismiss = { closeSheet("goals") })
        }
        if (activeSheet == "modes") ModeChoiceSurface(vm) { closeSheet("modes") }
        if (activeSheet == "history-tools") ThreadHistorySheet(vm) { closeSheet("history-tools") }
        if (activeSheet == "thread-attachments") ThreadAttachmentsSheet(vm) { closeSheet("thread-attachments") }
        if (activeSheet == "models") ModelChoiceSurface(vm) { closeSheet("models") }
        if (activeSheet == "about") AboutInfoSheet(vm) { closeSheet("about") }
        // Archive transfer must block all foreground pages and sheets.
        ObserveDataTransferBusyOverlay()
    }
}

/** 功能常驻、会话独立滚动，保持底部新会话和账号入口固定。 */
@Composable
private fun Sidebar(
    modifier: Modifier, selected: String, onSelect: (String) -> Unit,
    status: EngineStatus, approvals: Int, newThreadBlockReason: String?, onNewThread: () -> Unit,
    vm: CodexViewModel, onOpenThread: (String) -> Unit,
    sidebarState: SidebarUiState,
    continuationLabel: String?
) {
    val activeWorkspace by vm.activeWorkspace.collectAsState()
    val accountData by vm.data.collectEntries("limits")
    val accountErrors by vm.errors.collectEntries("limits")
    val limitsLoading by vm.loading.collectProjection { "limits" in it }
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
            CodexIconAction(onClick = {
                sidebarState.searching = !sidebarState.searching
                if (!sidebarState.searching) sidebarState.resetFilter()
            },
                modifier = Modifier.size(48.dp).testTag("sidebar-global-search")) {
                CodexIcon(if (sidebarState.searching) CodexGlyph.Close else CodexGlyph.Search,
                    if (sidebarState.searching) "关闭会话搜索" else "搜索全部会话", tint = Ink.parchment)
            }
        }
        CodexPrimaryButton(
            onClick = onNewThread,
            enabled = newThreadBlockReason == null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                .heightIn(min = 52.dp).testTag("sidebar-new-chat")
                .explainDisabled(newThreadBlockReason != null, newThreadBlockReason.orEmpty()),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ink.parchment, contentColor = Ink.canvas,
                disabledContainerColor = Ink.border, disabledContentColor = Ink.muted)
        ) {
            CodexIcon(CodexGlyph.Add, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("新建任务", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
        }
        SidebarRoute("files", selected, approvals, onSelect)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                .testTag("sidebar-project-context")
                .clickable { onSelect("files") },
            shape = RoundedCornerShape(14.dp),
            color = Ink.elevated
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Project, null, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前项目", style = MaterialTheme.typography.labelSmall,
                        color = Ink.muted)
                    Text(PresentationPolicy.workspaceName(activeWorkspace),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CodexIcon(CodexGlyph.ChevronRight, null, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 7.dp),
            color = Ink.border.copy(alpha = .65f))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SidebarSessions(vm, onOpenThread,
                modifier = Modifier.fillMaxSize(), uiState = sidebarState,
                continuationLabel = continuationLabel)
        }
        HorizontalDivider(color = Ink.border.copy(alpha = .65f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SidebarQuotaButton(
                snapshot = accountData["limits"],
                stale = status != EngineStatus.Connected || "limits" in accountErrors,
                loading = limitsLoading,
                onClick = { onSelect("limits") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CodexIconAction(onClick = { onSelect("account") },
                    modifier = Modifier.size(48.dp).testTag("nav-account")) {
                    CodexIcon(CodexGlyph.AccountCircle, "账号", tint = Ink.parchment)
                }
                CodexIconAction(onClick = { onSelect("settings") },
                    modifier = Modifier.size(48.dp).testTag("nav-settings")) {
                    CodexIcon(CodexGlyph.Settings, "设置", tint = Ink.parchment)
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
            CodexIcon(CodexGlyph.RadioButtonChecked, null,
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
            CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted,
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
        icon = { CodexIcon(page.icon, null, modifier = Modifier.size(21.dp)) },
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
