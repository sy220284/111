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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
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
    Page("mcp", "MCP 服务", "能力与扩展", "工具服务状态", Icons.Outlined.Hub, "mcpServerStatus/list"),
    Page("hooks", "触发钩子", "能力与扩展", "运行时钩子与事件", Icons.Outlined.Bolt, "hooks/list"),
    Page("environments", "执行环境", "能力与扩展", "设备和远端环境", Icons.Outlined.Dns, "environment/status", true),
    Page("account", "账号登录", "设置与账户", "ChatGPT 登录与会话", Icons.Outlined.AccountCircle, "account/read"),
    Page("providers", "模型服务商", "设置与账户", "供应商及默认项", Icons.Outlined.Tune, "modelProvider/list"),
    Page("config", "引擎配置", "设置与账户", "逐项读取和写入官方配置", Icons.Outlined.Settings, "config/read"),
    Page("security", "权限与安全", "设置与账户", "审批、沙箱和网络策略", Icons.Outlined.Security, "configRequirements/read"),
    Page("usage", "使用量", "设置与账户", "额度与调用统计", Icons.Outlined.Insights, "account/usage/read"),
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
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val running by vm.running.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val pending by vm.bridge.prompts.collectAsState()
    val terminalSession by vm.terminalSession.collectAsState()
    val page = pages.firstOrNull { it.id == selected } ?: pages.first()
    var showQuickTools by rememberSaveable { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var showGoal by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    val move: (String) -> Unit = move@{ destination ->
        when (destination) {
            "review" -> {
                if (activeThread == null) {
                    selected = "threads"
                    backHistory = (backHistory + "threads").takeLast(18)
                } else showReview = true
                scope.launch { drawer.close() }
                return@move
            }
            "goals" -> {
                if (activeThread == null) {
                    selected = "threads"
                    backHistory = (backHistory + "threads").takeLast(18)
                } else showGoal = true
                scope.launch { drawer.close() }
                return@move
            }
            "modes" -> {
                showMode = true
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

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.canvas)
    ) {
        val wide = maxWidth >= 850.dp
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val content: @Composable () -> Unit = {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!wide && !imeVisible && selected in NavigationPolicy.primaryRoutes && selected != "chat") {
                        NavigationBar(
                            containerColor = Ink.recessed,
                            tonalElevation = 0.dp,
                            modifier = Modifier.testTag("main-bottom-navigation")
                        ) {
                            NavigationPolicy.primaryRoutes.forEach { id ->
                                val entry = pages.first { it.id == id }
                                NavigationBarItem(
                                    selected = selected == id || (id == "settings" && NavigationPolicy.isSettingsChild(selected)),
                                    onClick = { move(id) },
                                    modifier = Modifier.testTag("bottom-$id"),
                                    icon = { Icon(entry.icon, contentDescription = entry.title) },
                                    label = { Text(when (id) {
                                        "home" -> "首页"
                                        "chat" -> "对话"
                                        "files" -> "文件"
                                        "threads" -> "会话"
                                        else -> "设置"
                                    }) },
                                    alwaysShowLabel = true
                                )
                            }

                        }
                    }
                },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(page.title, modifier = Modifier.testTag("screen-title"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                // 顶栏保留当前页面，正文只呈现一次功能介绍。
                            }
                        },
                        navigationIcon = {
                            if (!wide) {
                                val isNested = NavigationPolicy.isSettingsChild(selected)
                                IconButton(
                                    onClick = { if (isNested) goBack() else scope.launch { drawer.open() } },
                                    modifier = Modifier.padding(start = 6.dp).size(45.dp)
                                        .border(1.dp, Ink.border, CircleShape)
                                ) {
                                    Icon(if (isNested) Icons.Outlined.ArrowBack else Icons.Outlined.Menu,
                                        if (isNested) "返回上一页" else "打开侧边导航")
                                }
                            }
                        },
                        actions = {
                            if (selected in setOf("home", "chat", "files")) {
                                IconButton(onClick = { showQuickTools = true }) {
                                    Icon(Icons.Outlined.Widgets, contentDescription = "快捷工具")
                                }
                            }
                            if (selected == "threads") {
                                IconButton(
                                    onClick = { vm.newThread(); move("chat") },
                                    enabled = !running
                                ) {
                                    Icon(Icons.Outlined.AddCircleOutline, "新建任务")
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
                                    tint = if (status == EngineStatus.Connected) Ink.jade else Ink.red
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink.canvas)
                    )
                }
            ) { inset ->
                Box(Modifier.padding(inset).fillMaxSize()) {
                    // Only the current page participates in semantics and input.
                    // This prevents stale search fields and buttons from remaining
                    // interactive during rapid settings / feature navigation.
                    key(selected) {
                        CompositionLocalProvider(LocalWorkPageTitle provides page.title) {
                            PageContent(selected, vm, move)
                        }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(Modifier.width(288.dp), selected,
                    { id -> if (id == "status") showStatus = true else move(id) },
                    status, pending.size)
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = true,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.widthIn(max = 318.dp),
                        drawerContainerColor = Ink.recessed,
                        drawerContentColor = Ink.parchment
                    ) { Sidebar(Modifier.fillMaxHeight(), selected,
                        { id ->
                            if (id == "status") {
                                showStatus = true
                                scope.launch { drawer.close() }
                            } else move(id)
                        }, status, pending.size) }
                }
            ) { content() }
        }
        if (showQuickTools) {
            WorkspaceActionsSheet(
                onDismiss = { showQuickTools = false },
                navigate = move, hasThread = activeThread != null
            )
        }
        if (showStatus) {
            EngineStatusSheet(vm, onDismiss = { showStatus = false }, navigate = move)
        }
        if (showReview) ReviewScopeSheet(
            vm, onDismiss = { showReview = false }, navigate = move
        )
        if (showGoal) ThreadGoalSheet(
            vm, onDismiss = { showGoal = false }
        )
        if (showMode) ModeChoiceSurface(vm) { showMode = false }
    }
}

@Composable
private fun Sidebar(
    modifier: Modifier, selected: String, onSelect: (String) -> Unit,
    status: EngineStatus, approvals: Int
) {
    Column(
        modifier.background(Ink.canvas).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 15.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(49.dp), shape = CircleShape, color = Ink.canvas,
                border = BorderStroke(1.dp, Ink.copperDim)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null,
                        tint = Ink.copper, modifier = Modifier.size(23.dp))
                }
            }
            Spacer(Modifier.width(13.dp))
            Column {
                Text("CODEX", style = MaterialTheme.typography.titleLarge)
                Text("你的移动工作台", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("常用入口", modifier = Modifier.padding(start = 10.dp, top = 12.dp),
            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(NavigationPolicy.sidebarRoutes, key = { it }) { id ->
                val page = pages.first { it.id == id }
                val active = selected == id ||
                    (id == "settings" && NavigationPolicy.isSettingsChild(selected))
                NavigationDrawerItem(
                    label = {
                        Text(
                            when (id) {
                                "home" -> "首页"
                                "chat" -> "任务对话"
                                "threads" -> "会话历史"
                                "files" -> "工作区文件"
                                "approvals" -> "待审批操作"
                                else -> "设置"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    selected = active,
                    onClick = { onSelect(id) },
                    icon = { Icon(page.icon, contentDescription = null,
                        modifier = Modifier.size(23.dp)) },
                    badge = {
                        if (id == "approvals" && approvals > 0) {
                            StatusPill(approvals.toString(), tone = StatusTone.Danger)
                        } else if (id == "settings") {
                            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
                        }
                    },
                    modifier = Modifier.testTag("nav-$id").heightIn(min = 58.dp),
                    shape = RoundedCornerShape(19.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = Ink.canvas,
                        selectedIconColor = Ink.copper, selectedTextColor = Ink.copper,
                        unselectedContainerColor = Ink.canvas,
                        unselectedIconColor = Ink.muted, unselectedTextColor = Ink.parchment
                    )
                )
            }
        }
        HorizontalDivider(color = Ink.border)
        Row(
            Modifier.fillMaxWidth().clickable { onSelect("status") }
                .heightIn(min = 59.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.RadioButtonChecked, contentDescription = null,
                tint = if (status == EngineStatus.Connected) Ink.jade else Ink.red,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("本机引擎", style = MaterialTheme.typography.bodyMedium)
                Text(if (status == EngineStatus.Connected) "运行正常" else "查看连接和配置",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Ink.muted)
        }
    }
}
