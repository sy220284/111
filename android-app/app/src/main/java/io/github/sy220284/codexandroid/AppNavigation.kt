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
    Page("about", "关于项目", "设置与账户", "版本、源码与隐私", Icons.Outlined.Info)
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
    val status by vm.bridge.status.collectAsState()
    val pending by vm.bridge.prompts.collectAsState()
    val terminalSession by vm.terminalSession.collectAsState()
    val page = pages.firstOrNull { it.id == selected } ?: pages.first()
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    val move: (String) -> Unit = { destination ->
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
    BackHandler(enabled = drawer.currentValue == DrawerValue.Closed && backHistory.size > 1) {
        backHistory = backHistory.dropLast(1)
        selected = backHistory.last()
        focusManager.clearFocus()
        keyboard?.hide()
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
                    if (!wide && !imeVisible) {
                        NavigationBar(
                            containerColor = Ink.recessed,
                            tonalElevation = 0.dp,
                            modifier = Modifier.testTag("main-bottom-navigation")
                        ) {
                            NavigationPolicy.primaryRoutes.forEach { id ->
                                val entry = pages.first { it.id == id }
                                NavigationBarItem(
                                    selected = selected == id,
                                    onClick = { move(id) },
                                    modifier = Modifier.testTag("bottom-$id"),
                                    icon = { Icon(entry.icon, contentDescription = entry.title) },
                                    label = { Text(when (id) {
                                        "home" -> "首页"
                                        "chat" -> "对话"
                                        "files" -> "文件"
                                        else -> "会话"
                                    }) },
                                    alwaysShowLabel = true
                                )
                            }
                            NavigationBarItem(
                                selected = NavigationPolicy.isMoreSelected(selected),
                                onClick = { scope.launch { drawer.open() } },
                                modifier = Modifier.testTag("bottom-more"),
                                icon = { Icon(Icons.Outlined.Apps, contentDescription = "更多功能") },
                                label = { Text("更多") },
                                alwaysShowLabel = true
                            )
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
                            if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Outlined.Menu, "打开侧边导航")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { vm.newThread(); move("chat") },
                                enabled = !running
                            ) {
                                Icon(Icons.Outlined.AddCircleOutline,
                                    if (running) "执行中，请先完成当前任务" else "新建任务")
                            }
                            if (pending.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(pending.size.toString()) } }) {
                                    IconButton(onClick = { move("approvals") }) { Icon(Icons.Outlined.GppGood, "待审批") }
                                }
                            }
                            // One persistent status action: a running terminal takes
                            // precedence over diagnostics without adding a crowded icon.
                            IconButton(onClick = {
                                when {
                                    terminalSession.running -> move("terminal")
                                    status == EngineStatus.Connected -> move("diagnostics")
                                    else -> vm.connect()
                                }
                            }) {
                                Icon(
                                    when {
                                        terminalSession.running -> Icons.Outlined.Terminal
                                        status == EngineStatus.Connected -> Icons.Outlined.CheckCircle
                                        else -> Icons.Outlined.SyncProblem
                                    },
                                    when {
                                        terminalSession.running -> "终端命令运行中，返回终端"
                                        status == EngineStatus.Connected -> "查看内核诊断"
                                        else -> "连接内核"
                                    },
                                    tint = if (terminalSession.running) Ink.copper
                                        else if (status == EngineStatus.Connected) Ink.jade else Ink.red
                                )
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink.canvas.copy(alpha = .92f))
                    )
                }
            ) { inset ->
                Box(Modifier.padding(inset).fillMaxSize()) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            if (reducedMotion) fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                            else (fadeIn(tween(CodexMotion.reveal)) + slideInHorizontally(tween(CodexMotion.navigation)) { it / 10 })
                                .togetherWith(fadeOut(tween(CodexMotion.feedback)) + slideOutHorizontally(tween(CodexMotion.feedback)) { -it / 14 })
                        },
                        label = "section_switch"
                    ) { screen ->
                        CompositionLocalProvider(LocalWorkPageTitle provides (pages.firstOrNull { it.id == screen }?.title ?: "")) {
                            PageContent(screen, vm, move)
                        }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(Modifier.width(288.dp), selected, move, status, pending.size)
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
                    ) { Sidebar(Modifier.fillMaxHeight(), selected, move, status, pending.size) }
                }
            ) { content() }
        }
    }
}

@Composable
private fun Sidebar(
    modifier: Modifier, selected: String, onSelect: (String) -> Unit, status: EngineStatus, approvals: Int
) {
    var filter by rememberSaveable { mutableStateOf("") }
    var expandedGroup by rememberSaveable { mutableStateOf("工作台") }
    var revealAdvanced by rememberSaveable { mutableStateOf(false) }
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    LaunchedEffect(selected) {
        pages.firstOrNull { it.id == selected }?.let {
            expandedGroup = it.section
            if (!NavigationPolicy.visible(it.id, revealAdvanced = false, searching = false)) {
                revealAdvanced = true
            }
        }
    }
    Column(modifier.background(Ink.recessed).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Ink.canvas, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ink.copperDim)) {
                Icon(Icons.Outlined.Code, null, tint = Ink.copper, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("CODEX", style = MaterialTheme.typography.titleLarge)
                Text("移动开发工作台", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedTextField(filter, { filter = it },
            modifier = Modifier.fillMaxWidth().testTag("navigation-search"), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索功能与设置") },
            trailingIcon = {
                if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) {
                    Icon(Icons.Outlined.Close, "清除搜索")
                }
            },
            shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple("chat", "对话", Icons.Outlined.ChatBubbleOutline),
                Triple("files", "文件", Icons.Outlined.FolderOpen),
                Triple("approvals", if (approvals > 0) "审批 $approvals" else "审批", Icons.Outlined.GppGood)
            ).forEach { (id, title, icon) ->
                OutlinedButton(onClick = { onSelect(id) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)) {
                    Icon(icon, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(title, maxLines = 1)
                }
            }
        }
        TextButton(
            onClick = { revealAdvanced = !revealAdvanced },
            modifier = Modifier.fillMaxWidth().testTag("navigation-advanced-toggle")
        ) {
            Icon(if (revealAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.Tune,
                contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (revealAdvanced) "收起高级功能" else "显示全部 ${pages.size} 项功能")
        }
        DividerLine()
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (group in pages.map { it.section }.distinct()) {
                val entries = pages.filter {
                    it.section == group &&
                        NavigationPolicy.matches(filter, it.title, it.subtitle, it.id) &&
                        NavigationPolicy.visible(it.id, revealAdvanced, filter.isNotBlank())
                }
                if (entries.isNotEmpty()) item(key = "group-$group") {
                    Column {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expandedGroup = if (expandedGroup == group) "" else group }
                                .heightIn(min = 49.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(group, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                            Text(entries.size.toString(), style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                            Spacer(Modifier.width(7.dp))
                            Icon(if (filter.isNotBlank() || expandedGroup == group)
                                Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null, tint = Ink.muted, modifier = Modifier.size(19.dp))
                        }
                        AnimatedVisibility(
                            visible = filter.isNotBlank() || expandedGroup == group,
                            enter = expandVertically(tween(CodexMotion.duration(CodexMotion.reveal, reducedMotion))) +
                                fadeIn(tween(CodexMotion.duration(CodexMotion.reveal, reducedMotion))),
                            exit = shrinkVertically(tween(CodexMotion.duration(CodexMotion.feedback, reducedMotion))) +
                                fadeOut(tween(CodexMotion.duration(CodexMotion.feedback, reducedMotion)))
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                entries.forEach { entry ->
                                    NavigationDrawerItem(
                                        label = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        selected = entry.id == selected, onClick = { onSelect(entry.id) },
                                        icon = { Icon(entry.icon, null, modifier = Modifier.size(21.dp)) },
                                        badge = { if (entry.id == "approvals" && approvals > 0)
                                            Text(approvals.toString(), color = Ink.copper) },
                                        modifier = Modifier.heightIn(min = 51.dp).testTag("nav-${entry.id}"),
                                        shape = RoundedCornerShape(13.dp),
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = Ink.canvas,
                                            selectedIconColor = Ink.copper, selectedTextColor = Ink.copper,
                                            unselectedContainerColor = Color.Transparent,
                                            unselectedIconColor = Ink.muted, unselectedTextColor = Ink.parchment
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        DividerLine()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clickable { onSelect("diagnostics") }
            .heightIn(min = 53.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (status == EngineStatus.Connected) "引擎已连接" else "连接状态待检查",
                status == EngineStatus.Connected)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, "查看诊断", tint = Ink.muted)
        }
    }
}
