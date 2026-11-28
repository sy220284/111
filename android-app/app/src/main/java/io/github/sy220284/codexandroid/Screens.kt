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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun PageContent(id: String, vm: CodexViewModel, navigate: (String) -> Unit) {
    val selectedTask by vm.conversationState.collectProjection { it.threadId to it.remote }
    val (thread, remote) = selectedTask
    val localTerminalRunning by vm.terminalSession.collectProjection { it.running }
    if (remote != null && (id == "files" || (id == "terminal" && !localTerminalRunning))) {
        val binding = requireNotNull(remote)
        RemoteWorkspacePage(vm, thread, binding, files = id == "files", navigate = navigate)
        return
    }
    when (id) {
        "settings" -> SettingsHubPage(vm, navigate)
        "chat" -> ConversationPage(vm, navigate)
        "files" -> WorkspacePage(vm)
        "diff" -> DiffPage(vm, navigate)
        "terminal" -> TerminalPage(vm, navigate = navigate)
        "review" -> ReviewPage(vm, navigate)
        "approvals" -> ApprovalsPage(vm)
        "models" -> ModelsPage(vm)
        "providers" -> ProvidersPage(vm)
        "skills" -> SkillsPage(vm, navigate)
        "plugins" -> PluginsPage(vm, navigate)
        "modes" -> CollaborationModesPage(vm)
        "apps" -> ConnectedAppsPage(vm, navigate)
        "mcp" -> McpServicesPage(vm, navigate)
        "environments" -> EnvironmentsPage(vm) { navigate("chat") }
        "diagnostics" -> EngineDiagnosticsPage(vm)
        "usage", "limits" -> AccountMetricsPage(id, vm, navigate)
        "account" -> AccountPage(vm, navigate)
        "features" -> FeatureSwitchPage(vm)
        "config" -> ConfigPage(vm, navigate)
        "appearance" -> AppearancePage(vm)
        "personalization" -> PersonalizationPage(vm)
        "context" -> ContextManagementPage(vm)
        "tools" -> SearchCapabilityPage(vm)
        "about" -> AboutPage(vm)
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
    val compact by UiPreferences.compact.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val spacing = CodexViewportPolicy.pageSpacing(maxWidth.value.coerceAtLeast(1f),
            maxHeight.value.coerceAtLeast(1f), compact)
        Column(
            modifier = Modifier.fillMaxHeight().widthIn(max = CodexContentMaxWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.horizontal.dp, vertical = spacing.vertical.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.gap.dp),
            content = content
        )
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
            CodexIcon(CodexGlyph.Thread, null, tint = Ink.copper)
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
            } else CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted)
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
    CodexNotice(PresentationPolicy.readableError(error), NoticeTone.Error)
}

@Composable
fun CatalogPage(page: Page, vm: CodexViewModel) {
    val result by vm.data.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val engineStatus by vm.bridge.status.collectAsStateWithLifecycle()
    val payload = result[page.id]
    val entries = records(payload)
    var selectedDetail by remember(page.id) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(page.id) { page.rpc?.let { vm.refresh(page.id, it) } }
    ScreenColumn {
        SectionIntro(page.title, page.subtitle)
        if (page.experimental) StatusPill("实验功能", tone = StatusTone.Neutral)
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(page.icon, null, tint = Ink.copper)
                Spacer(Modifier.width(12.dp))
                Text("当前状态", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                CodexRefreshIcon(busy = page.id in loading,
                    onRefresh = { page.rpc?.let { vm.refresh(page.id, it) } },
                    label = "刷新${page.title}")
            }
            if (page.id in loading) AtlasOperationProgress("正在读取${page.title}")
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
                        CodexTextAction(onClick = { selectedDetail = display to summary }) {
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
        CodexInfoDialog(title = title, onDismiss = { selectedDetail = null }) {
            Text(description)
        }
    }
}

@Composable
fun ThreadScopedPage(id: String, vm: CodexViewModel) {
    val active by vm.activeThread.collectAsStateWithLifecycle()
    val page = pages.first { it.id == id }
    val errors by vm.errors.collectAsStateWithLifecycle()
    val payload by vm.data.collectAsStateWithLifecycle()
    val method = page.rpc ?: return
    ScreenColumn {
        SectionIntro(page.title, page.subtitle)
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("需要先打开一个会话", style = MaterialTheme.typography.titleMedium)
                Text("从会话历史或任务对话中选择当前任务。", color = Ink.muted)
            }
        } else {
            CodexSecondaryButton(onClick = { vm.refresh(id, method, JSONObject().put("threadId", active)) }) {
                CodexIcon(CodexGlyph.Retry, null); Spacer(Modifier.width(8.dp)); Text("读取当前会话")
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

