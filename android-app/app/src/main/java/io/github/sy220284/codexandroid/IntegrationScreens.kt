package io.github.sy220284.codexandroid

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
private fun IntegrationState(vm: CodexViewModel, key: String, retry: () -> Unit) {
    ProtocolFeedback(vm, listOf(key), onRetry = retry)
}

@Composable
fun CollaborationModesPage(vm: CodexViewModel) {
    val payload by vm.data.collectAsState()
    val selected by vm.selectedMode.collectAsState()
    val catalog = records(payload["modes"])
    val running by vm.running.collectAsState()
    val loading by vm.loading.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val planSupported = catalog.any { it.optString("mode") == "plan" }
    val refreshing = "modes" in loading
    LaunchedEffect(status) {
        if (status == EngineStatus.Connected) vm.refresh("modes", "collaborationMode/list")
    }
    ScreenColumn {
        SectionIntro("协作与工作方式", "根据任务选择执行方式。计划模式先形成方案，再由你明确推进。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("新任务使用的工作方式", style = MaterialTheme.typography.titleMedium)
            StatusPill(if (selected == "plan") "计划模式" else "标准执行模式", true)
            Text("更改后从下一轮对话生效。不会自动开启派生智能体。", color = Ink.muted)
        }
        IntegrationState(vm, "modes") { vm.refresh("modes", "collaborationMode/list") }
        if (running) CodexNotice("任务正在执行，结束或停止后即可切换协作方式。", NoticeTone.Info)
        else if (status != EngineStatus.Connected)
            CodexNotice("连接本机运行核心后可查看全部支持的方式。", NoticeTone.Warning,
                actionLabel = "重新连接", onAction = { vm.connect() })
        listOf(
            Triple("default", "标准执行", "直接分析任务，并按允许的权限执行步骤。"),
            Triple("plan", "先制定计划", "先展示方案与关键决策，适合复杂改造和多阶段任务。")
        ).forEach { (id, name, description) ->
            val canSelect = ModeSelectionPolicy.maySelect(id, planSupported, running, refreshing)
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (id == "plan") Icons.Outlined.Assignment else Icons.Outlined.Build,
                        null, tint = Ink.copper)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(description, color = Ink.muted)
                    }
                    RadioButton(selected == id, onClick = { vm.selectedMode.value = id },
                        enabled = canSelect)
                }
                if (id == "plan" && !planSupported)
                    Text("当前服务未公布该模式，请刷新模型与模式目录。", color = Ink.muted)
            }
        }
        if (catalog.isNotEmpty()) {
            Text("当前核心提供的模式", style = MaterialTheme.typography.titleLarge)
            catalog.forEach { entry ->
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text(entry.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Text(if (entry.optString("mode") == "plan") "适合复杂任务规划"
                        else "适合直接完成任务", color = Ink.muted)
                    entry.optString("model").takeIf { it.isNotBlank() }?.let {
                        Text("推荐模型：" + it, color = Ink.muted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedAppsPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val engineStatus by vm.bridge.status.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val context = LocalContext.current
    val data by vm.data.collectAsState()
    val apps = records(data["apps"])
    val installed = data["appInstalled"]?.optJSONArray("apps")
    var target by remember { mutableStateOf<JSONObject?>(null) }
    var inspecting by remember { mutableStateOf<JSONObject?>(null) }
    var desired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.refresh("apps", "app/list", JSONObject().put("limit", 100))
        vm.refresh("appInstalled", "app/installed", JSONObject())
    }
    ScreenColumn {
        SectionIntro("已连接应用", "查看服务连接与授权；具体工具由智能体在任务中使用。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("应用连接", style = MaterialTheme.typography.titleMedium)
                Text(when {
                    engineStatus != EngineStatus.Connected -> "运行核心未连接"
                    "apps" in loading || "appInstalled" in loading -> "正在读取应用"
                    errors["apps"] != null || errors["appInstalled"] != null -> "读取应用失败"
                    data["apps"] != null -> "${apps.size} 个可用应用"
                    else -> "尚未读取"
                }, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = {
                vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("forceRefetch", true))
                vm.refresh("appInstalled", "app/installed")
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }
        ProtocolFeedback(vm, listOf("apps", "appInstalled"), onRetry = {
            vm.refresh("apps", "app/list", JSONObject().put("limit", 100))
            vm.refresh("appInstalled", "app/installed")
        })
        if (engineStatus != EngineStatus.Connected && data["apps"] == null) {
            CodexRecoveryState("连接后可查看应用",
                "连接运行核心后会显示真实的应用授权与可用状态。",
                "重新连接", { vm.connect() }, { navigate("diagnostics") })
        } else if (engineStatus == EngineStatus.Connected &&
            data["apps"] == null && "apps" !in loading && errors["apps"] != null) {
            CodexRecoveryState("读取应用失败",
                "当前应用目录未返回有效结果，可重新读取或查看诊断。",
                "重新读取", {
                    vm.refresh("apps", "app/list", JSONObject().put("limit", 100))
                    vm.refresh("appInstalled", "app/installed")
                }, { navigate("diagnostics") })
        }
        if (apps.isEmpty() && data["apps"] != null) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("尚未发现可用应用", style = MaterialTheme.typography.titleMedium)
            Text("检查登录状态，或进入插件管理查看可用的扩展。",
                color = Ink.muted)
            OutlinedButton(onClick = { navigate("plugins") }) {
                Text("前往插件管理")
            }
        }
        apps.forEach { app ->
            val id = app.optString("id")
            val active = (0 until (installed?.length() ?: 0)).mapNotNull { installed?.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
            val name = app.optString("name", id)
            val callable = active?.optBoolean("callable") == true
            val enabled = app.optBoolean("isEnabled", true)
            val appStatus = when {
                !enabled -> "已停用"
                callable -> "已连接"
                !app.optBoolean("isAccessible") -> "待授权"
                active?.optBoolean("enabled") == true -> "等待工具就绪"
                else -> "尚未连接"
            }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { inspecting = app }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(15.dp), color = Ink.canvas,
                        border = BorderStroke(1.dp, Ink.copperDim)) {
                        Icon(Icons.Outlined.Apps, null, tint = Ink.copper,
                            modifier = Modifier.padding(12.dp).size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                    }
                    StatusPill(appStatus, callable)
                    Spacer(Modifier.width(7.dp))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "查看应用详情",
                        tint = Ink.muted)
                }
            }
        }
    }
    inspecting?.let { app ->
        val id = app.optString("id")
        val name = app.optString("name", id)
        val description = PresentationPolicy.meaningful(app.optString("description"))
        val installUrl = app.optString("installUrl")
        val enabled = app.optBoolean("isEnabled", true)
        val active = (0 until (installed?.length() ?: 0))
            .mapNotNull { installed?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == id }
        val appStatus = when {
            !enabled -> "已停用"
            active?.optBoolean("callable") == true -> "已连接"
            !app.optBoolean("isAccessible") -> "待授权"
            active?.optBoolean("enabled") == true -> "等待工具就绪"
            else -> "尚未连接"
        }
        ModalBottomSheet(
            onDismissRequest = { inspecting = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Ink.recessed, contentColor = Ink.parchment
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                StatusPill(appStatus, appStatus == "已连接")
                Text("应用说明", style = MaterialTheme.typography.titleMedium)
                Text(description.ifEmpty { "该应用暂未提供说明。" }, color = Ink.muted)
                if (installUrl.startsWith("https://")) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(installUrl)))
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("管理应用授权")
                    }
                }
                if (id.matches(Regex("[A-Za-z0-9_-]+"))) {
                    OutlinedButton(
                        onClick = {
                            desired = !enabled
                            target = app
                            inspecting = null
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (enabled) "停用应用" else "启用应用") }
                }
            }
        }
    }
    target?.let { item ->
        val id = item.optString("id")
        CodexConfirmDialog(
            title = if (desired) "启用连接应用" else "停用连接应用",
            message = "此操作将修改 Codex 对该应用的本地启用配置。",
            detail = PresentationPolicy.meaningful(item.optString("name")).ifEmpty { "当前应用" },
            confirmLabel = "确认修改",
            destructive = !desired,
            onConfirm = {
                vm.rpc("apps", "config/value/write",
                    JSONObject().put("keyPath", "apps." + id + ".enabled")
                        .put("value", desired).put("mergeStrategy", "replace"),
                    after = {
                        vm.refresh("apps", "app/list")
                        vm.refresh("appInstalled", "app/installed")
                    })
                target = null
            },
            onDismiss = { target = null }
        )
    }
}

@Composable
fun McpServicesPage(vm: CodexViewModel, navigate: (String) -> Unit = {}) {
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    val usable = McpAvailableServices.visible(records(data["mcp"]))

    LaunchedEffect(activeThread, engineStatus) {
        if (engineStatus == EngineStatus.Connected) vm.refreshMcpServices()
    }

    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("可用服务 " + usable.size + " 项", color = Ink.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            CodexRefreshIcon(
                busy = "mcp" in loading,
                onRefresh = { vm.refreshMcpServices() },
                label = "刷新可用服务"
            )
        }
        ProtocolFeedback(vm, listOf("mcp", "mcpReload"),
            onRetry = { vm.refreshMcpServices() })
        if (usable.isEmpty() && "mcp" !in loading) {
            if (engineStatus != EngineStatus.Connected) {
                CodexRecoveryState("连接后可查看 MCP 服务",
                    "由智能体管理服务，连接成功的可用服务才会显示。",
                    "重新连接", { vm.connect() }, { navigate("diagnostics") })
            } else if (data["mcp"] != null) {
                CodexEmptyState(
                    "暂无可用 MCP 服务",
                    "在任务对话中要求智能体接入 MCP 服务，连通后会自动显示。",
                    icon = Icons.Outlined.Hub,
                    actionLabel = "刷新服务", onAction = { vm.refreshMcpServices() }
                )
            } else {
                CodexRecoveryState("MCP 目录尚未读取",
                    "连接成功后可以重新检查服务目录。",
                    "重新读取", { vm.refreshMcpServices() }, { navigate("diagnostics") })
            }
        }
        usable.forEach { McpServiceStatusCard(it) }
    }
}

/** MCP is a read-only directory. Intelligent-agent setup and authorization remain task-owned. */
@Composable
internal fun McpServiceStatusCard(server: JSONObject) {
    if (!McpAvailableServices.isUsable(server)) return
    var details by remember(server.optString("name")) { mutableStateOf(false) }
    val toolCount = server.optJSONObject("tools")?.length() ?: 0
    val resourceCount = (server.optJSONArray("resources")?.length() ?: 0) +
        (server.optJSONArray("resourceTemplates")?.length() ?: 0)
    val summary = buildList {
        if (toolCount > 0) add("$toolCount 项工具")
        if (resourceCount > 0) add("$resourceCount 项资源")
        if (server.optJSONObject("serverCapabilities")?.optJSONObject("prompts") != null)
            add("支持指令模板")
    }.joinToString(" · ")
    MaterialPanel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { details = !details },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Hub, null, tint = Ink.copper)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(McpAvailableServices.displayName(server),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (summary.isNotEmpty()) {
                    Text(summary, color = Ink.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            StatusPill("可用", true)
            Spacer(Modifier.width(4.dp))
            Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                contentDescription = if (details) "收起服务详情" else "查看服务详情",
                tint = Ink.muted)
        }
        CodexReveal(details) {
            DividerLine()
            Text(server.optString("description").ifBlank {
                "服务已连接，可用能力由智能体在任务中选择调用。"
            }, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            Text("工具 $toolCount 项 · 资源 $resourceCount 项",
                style = MaterialTheme.typography.labelMedium, color = Ink.parchment)
            Text("由智能体管理连接与授权；此处仅展示已就绪的服务。",
                style = MaterialTheme.typography.labelSmall, color = Ink.muted)
        }
    }
}
