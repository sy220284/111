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
fun PageContent(id: String, vm: CodexViewModel, navigate: (String) -> Unit,
    onOpenWorkspaceTools: () -> Unit) {
    when (id) {
        "home" -> HomePage(vm, navigate)
        "settings" -> SettingsHubPage(vm, navigate)
        "chat" -> ConversationPage(vm, navigate, onOpenWorkspaceTools)
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
    val hasAccount = data["account"]?.optJSONObject("account") != null
    val errors by vm.errors.collectAsState()

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
        errors["protocol"]?.let { reason ->
            CodexNotice(reason, NoticeTone.Warning,
                actionLabel = "查看诊断", onAction = { navigate("diagnostics") })
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
                ThreadRow(item, enabled = !switching && !firstThreadStarting) {
                    val id = item.optString("id")
                    vm.chooseThread(id) { navigate("chat") }
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
                },
                artworkRes = if (engineStatus == EngineStatus.Connected) null
                    else R.drawable.ic_empty_connection
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

