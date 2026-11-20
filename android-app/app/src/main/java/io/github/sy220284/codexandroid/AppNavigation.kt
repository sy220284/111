package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedContent
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
    Page("search", "全文检索", "工作台", "定位历史会话", Icons.Outlined.Search, "thread/search"),
    Page("goals", "任务目标", "工作台", "线程目标与上下文", Icons.Outlined.Flag, "thread/goal/get"),
    Page("queue", "任务队列", "工作台", "待处理步骤与顺序", Icons.Outlined.ViewList, "thread/queue/list", true),
    Page("files", "工作区文件", "开发工具", "导入、打开和编辑工程文件", Icons.Outlined.FolderOpen),
    Page("diff", "代码差异", "开发工具", "当前任务的改动追踪", Icons.Outlined.Code),
    Page("terminal", "终端", "开发工具", "在设备沙箱中执行命令", Icons.Outlined.Terminal),
    Page("review", "代码审查", "开发工具", "发起 Codex 审查", Icons.Outlined.FactCheck, "review/start"),
    Page("models", "模型目录", "Codex 生态", "可用模型与思考能力", Icons.Outlined.AutoAwesome, "model/list"),
    Page("modes", "协作模式", "Codex 生态", "计划、编码与协作模式", Icons.Outlined.PeopleOutline, "collaborationMode/list"),
    Page("skills", "技能", "Codex 生态", "发现并管理技能", Icons.Outlined.School, "skills/list"),
    Page("plugins", "插件", "Codex 生态", "安装状态与能力", Icons.Outlined.Extension, "plugin/list"),
    Page("apps", "连接应用", "Codex 生态", "应用连接与权限", Icons.Outlined.Apps, "app/list"),
    Page("mcp", "MCP 服务", "Codex 生态", "工具服务状态", Icons.Outlined.Hub, "mcpServerStatus/list"),
    Page("hooks", "触发钩子", "Codex 生态", "运行时钩子与事件", Icons.Outlined.Bolt, "hooks/list"),
    Page("environments", "执行环境", "Codex 生态", "设备和远端环境", Icons.Outlined.Dns, "environment/status", true),
    Page("account", "账号登录", "偏好与设置", "ChatGPT 登录与会话", Icons.Outlined.AccountCircle, "account/read"),
    Page("providers", "模型服务商", "偏好与设置", "供应商及默认项", Icons.Outlined.Tune, "modelProvider/list"),
    Page("config", "引擎配置", "偏好与设置", "逐项读取和写入官方配置", Icons.Outlined.Settings, "config/read"),
    Page("security", "权限与安全", "偏好与设置", "审批、沙箱和网络策略", Icons.Outlined.Security, "configRequirements/read"),
    Page("approvals", "待审批操作", "偏好与设置", "命令与文件变更确认", Icons.Outlined.GppGood),
    Page("usage", "使用量", "偏好与设置", "额度与调用统计", Icons.Outlined.Insights, "account/usage/read"),
    Page("limits", "使用限额", "偏好与设置", "限额和重置时间", Icons.Outlined.Speed, "account/rateLimits/read"),
    Page("appearance", "外观与交互", "偏好与设置", "显示密度、材质和动效", Icons.Outlined.Palette),
    Page("diagnostics", "诊断与日志", "偏好与设置", "本机连接与运行状态", Icons.Outlined.BugReport, "server/diagnostics", true),
    Page("about", "关于项目", "偏好与设置", "版本、源码与隐私", Icons.Outlined.Info)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun CodexShell(vm: CodexViewModel) {
    var selected by rememberSaveable { mutableStateOf("home") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val status by vm.bridge.status.collectAsState()
    val pending by vm.bridge.prompts.collectAsState()
    val page = pages.firstOrNull { it.id == selected } ?: pages.first()
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    val move: (String) -> Unit = { destination ->
        selected = destination
        if (page.id != destination) {
            pages.firstOrNull { it.id == destination }?.rpc?.let { method ->
                // Read-only pages refresh on entry; thread-specific calls are handled by their screens.
                if (destination !in setOf("goals", "queue", "environments", "review", "search")) {
                    vm.refresh(destination, method)
                }
            }
        }
        scope.launch { drawer.close() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink.canvas, Color(0xFF171E24), Ink.canvas)))
    ) {
        val wide = maxWidth >= 850.dp
        val content: @Composable () -> Unit = {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text("CODEX / ANDROID", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                            }
                        },
                        navigationIcon = {
                            if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Outlined.Menu, "打开侧边导航")
                            }
                        },
                        actions = {
                            if (pending.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(pending.size.toString()) } }) {
                                    IconButton(onClick = { move("approvals") }) { Icon(Icons.Outlined.GppGood, "待审批") }
                                }
                            }
                            IconButton(onClick = { if (status == EngineStatus.Connected) vm.refresh("account", "account/read") else vm.connect() }) {
                                Icon(if (status == EngineStatus.Connected) Icons.Outlined.CheckCircle else Icons.Outlined.SyncProblem, "内核状态",
                                    tint = if (status == EngineStatus.Connected) Ink.jade else Ink.red)
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
                            if (reducedMotion) fadeIn(tween(50)).togetherWith(fadeOut(tween(50)))
                            else (fadeIn(tween(185)) + slideInHorizontally(tween(210)) { it / 9 })
                                .togetherWith(fadeOut(tween(100)) + slideOutHorizontally(tween(115)) { -it / 12 })
                        },
                        label = "section_switch"
                    ) { screen ->
                        PageContent(screen, vm, move)
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
    var filter by remember { mutableStateOf("") }
    Column(
        modifier = modifier.background(Ink.recessed).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = Ink.copper.copy(alpha = .16f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ink.copperDim)
            ) {
                Icon(Icons.Outlined.Code, null, tint = Ink.copper, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("CODEX", style = MaterialTheme.typography.titleLarge, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified)
                Text("移动开发工作台", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("查找功能") },
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (group in pages.map { it.section }.distinct()) {
                val entries = pages.filter { it.section == group && (filter.isBlank() || (it.title + it.subtitle).contains(filter, true)) }
                if (entries.isNotEmpty()) {
                    item(key = "group-" + group) {
                        Text(group, color = Ink.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 17.dp, bottom = 7.dp))
                    }
                    items(entries, key = { it.id }) { entry ->
                        NavigationDrawerItem(
                            label = { Text(entry.title, maxLines = 1) },
                            selected = entry.id == selected,
                            onClick = { onSelect(entry.id) },
                            icon = { Icon(entry.icon, null, modifier = Modifier.size(21.dp)) },
                            badge = {
                                if (entry.id == "approvals" && approvals > 0) Text(approvals.toString(), color = Ink.copper)
                            },
                            modifier = Modifier.height(47.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Ink.copper.copy(alpha = .14f),
                                selectedIconColor = Ink.copper,
                                selectedTextColor = Ink.parchment,
                                unselectedContainerColor = Color.Transparent,
                                unselectedIconColor = Ink.muted,
                                unselectedTextColor = Ink.muted
                            )
                        )
                    }
                }
            }
        }
        DividerLine()
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clickable { onSelect("diagnostics") }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(if (status == EngineStatus.Connected) "引擎已连接" else "引擎待连接", status == EngineStatus.Connected)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
        }
    }
}
