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
        "modes" -> CollaborationModesPage(vm)
        "apps" -> ConnectedAppsPage(vm, navigate)
        "mcp" -> McpServicesPage(vm)
        "environments" -> EnvironmentsPage(vm)
        "diagnostics" -> EngineDiagnosticsPage(vm)
        "usage", "limits" -> AccountMetricsPage(id, vm, navigate)
        "account" -> AccountPage(vm, navigate)
        "config" -> ConfigPage(vm, navigate)
        "appearance" -> AppearancePage()
        "security" -> SecurityPage(vm, navigate)
        "about" -> AboutPage(vm)
        "search" -> ThreadLibraryPage(vm, navigate, initialFullText = true)
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
    val firstThreadStarting by vm.firstThreadStarting.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val prompts by vm.bridge.prompts.collectAsState()
    val data by vm.data.collectAsState()
    val threads = records(data["threads"])
    val errors by vm.errors.collectAsState()
    val connected = status == EngineStatus.Connected
    val connecting = status == EngineStatus.Starting
    val hasAccount = data["account"]?.optJSONObject("account") != null

    ScreenColumn {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(if (running) "继续当前工作" else "从一项任务开始",
                style = MaterialTheme.typography.headlineSmall)
            Text(if (running) "返回任务对话，查看执行结果和下一步。"
                 else "输入你的需求，或打开之前的项目继续。",
                style = MaterialTheme.typography.bodyLarge, color = Ink.muted)
        }
        MaterialPanel(Modifier.fillMaxWidth().testTag("home-task-hero")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        connected -> Icons.Outlined.CheckCircle
                        connecting -> Icons.Outlined.Sync
                        else -> Icons.Outlined.SyncProblem
                    }, null,
                    tint = if (connected) Ink.jade else Ink.warning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    when {
                        connected -> if (running) "任务执行中" else "已准备好开始任务"
                        connecting -> "正在连接运行服务"
                        else -> "运行服务需要重新连接"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.muted, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            CodexPrimaryButton(
                onClick = {
                    if (connected) {
                        if (!running) vm.newThread()
                        navigate("chat")
                    } else if (status is EngineStatus.Unavailable) vm.connect()
                },
                enabled = !switching && !connecting,
                busy = switching || connecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .testTag("home-primary-action"),
                shape = RoundedCornerShape(17.dp)
            ) {
                Icon(
                    when {
                        !connected -> Icons.Outlined.Refresh
                        running -> Icons.Outlined.PlayArrow
                        else -> Icons.Outlined.Add
                    }, null)
                Spacer(Modifier.width(9.dp))
                Text(when {
                    !connected -> "重新连接"
                    running -> "继续当前任务"
                    else -> "开启新对话"
                })
            }
        }
        when {
            prompts.isNotEmpty() -> CodexNotice(
                "有 ${prompts.size} 项操作需要确认", NoticeTone.Warning,
                actionLabel = "查看审批", onAction = { navigate("approvals") })
            connected && data["account"] != null && !hasAccount -> CodexNotice(
                "登录后可使用已关联的账号和模型服务。",
                NoticeTone.Info, actionLabel = "设置账号", onAction = { navigate("account") })
            connected && errors["protocol"] != null -> CodexNotice(
                PresentationPolicy.readableError(errors.getValue("protocol")),
                NoticeTone.Warning,
                actionLabel = "查看诊断", onAction = { navigate("diagnostics") })
        }
        Text("当前项目", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 5.dp, top = 9.dp))
        val homeWorkspace by vm.activeWorkspace.collectAsState()
        MaterialPanel(Modifier.fillMaxWidth().testTag("home-project-context")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, tint = Ink.jade,
                    modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(PresentationPolicy.workspaceName(homeWorkspace),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("文件与命令均在此项目目录操作",
                        style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { navigate("files") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("home-shortcut-files")
                ) {
                    Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("浏览文件")
                }
                OutlinedButton(
                    onClick = { navigate("terminal") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("home-shortcut-terminal")
                ) {
                    Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开终端")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("最近会话", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { navigate("threads") }) { Text("查看全部") }
        }
        if (threads.isEmpty()) {
            Text(
                if (connected && data["threads"] != null)
                    "完成首个任务后，这里会出现最近的会话。"
                else "连接后可查看最近的任务记录。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium
            )
        } else {
            threads.take(3).forEach { item ->
                ThreadRow(item, enabled = !switching && !firstThreadStarting) {
                    val id = item.optString("id")
                    vm.chooseThread(id) { navigate("chat") }
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
    var selectedDetail by remember(page.id) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(page.id) { page.rpc?.let { vm.refresh(page.id, it) } }
    ScreenColumn {
        SectionIntro(page.title, page.subtitle)
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
                        TextButton(onClick = { selectedDetail = display to summary }) {
                            Text("查看详情")
                        }
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
                },
                artworkRes = if (engineStatus == EngineStatus.Connected) null
                    else R.drawable.ic_empty_connection
            )
        }
    }
    selectedDetail?.let { (title, description) ->
        AlertDialog(
            onDismissRequest = { selectedDetail = null },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = { selectedDetail = null }) { Text("关闭") }
            }
        )
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
        SectionIntro(page.title, page.subtitle)
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

