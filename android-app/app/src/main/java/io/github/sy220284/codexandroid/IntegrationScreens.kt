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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val payload by vm.data.collectEntries("modes")
    val selected by vm.selectedMode.collectAsState()
    val catalog = remember(payload) { records(payload["modes"]) }
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
                    CodexIcon(if (id == "plan") CodexGlyph.Assignment else CodexGlyph.Build,
                        null, tint = Ink.copper)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(description, color = Ink.muted)
                    }
                    CodexRadio(selected == id, onClick = { vm.chooseComposerMode(id) },
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
    val data by vm.data.collectEntries("apps", "appInstalled")
    val epoch by vm.extensions.epoch.collectAsState()
    val installed = data["appInstalled"]
    val directory = data["apps"]
    val apps = remember(directory, installed) { ExtensionCatalogPolicy.apps(directory, installed) }
    var appQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val verified = installed != null && "appInstalled" !in errors && engineStatus == EngineStatus.Connected
    val statuses = remember(apps, installed, verified) {
        apps.associate { it.optString("id") to ExtensionCatalogPolicy.appStatus(it, installed, verified) }
    }
    val connectedCount = remember(statuses) { statuses.values.count { it == "已连接" } }
    val visible = remember(apps, appQuery) {
        apps.filter { it.optString("name").contains(appQuery.trim(), ignoreCase = true) }
    }
    val groups = remember(visible, statuses) { visible.groupBy { statuses[it.optString("id")] == "已连接" } }
    val installedById = remember(installed) { records(installed).associateBy { it.optString("id") } }
    var target by remember(epoch) { mutableStateOf<JSONObject?>(null) }
    var inspectingId by remember(epoch) { mutableStateOf<String?>(null) }
    var desired by remember { mutableStateOf(false) }
    val connected = engineStatus == EngineStatus.Connected
    val writing = "appMutation" in loading
    val cursor = ExtensionCatalogPolicy.cursor(directory)
    fun refreshApps(force: Boolean = false) {
        if (!connected) return
        if ("appInstalled" !in loading) vm.refresh("appInstalled", "app/installed",
            JSONObject().put("forceRefresh", force))
        if ("apps" !in loading) vm.refresh("apps", "app/list",
            JSONObject().put("limit", 100).put("forceRefetch", force))
    }
    LaunchedEffect(epoch, engineStatus) {
        if (connected) {
            if (CatalogRequestContract.shouldLoadCatalog("appInstalled", data.keys, loading))
                vm.refresh("appInstalled", "app/installed", JSONObject())
            if (CatalogRequestContract.shouldLoadCatalog("apps", data.keys, loading))
                vm.refresh("apps", "app/list", JSONObject().put("limit", 100))
        }
    }
    ScreenColumn {
        ExtensionCatalogHeader("应用连接",
            if (verified) "$connectedCount 个已连接" else null,
            "这里管理应用连接与启用状态。具体工具由智能体在任务中调用。",
            refreshing = "apps" in loading || "appInstalled" in loading,
            enabled = connected && !writing, onRefresh = { refreshApps(true) })
        ProtocolFeedback(vm, listOf("appMutation"))
        if (apps.isEmpty()) {
            if (connected && installed == null && "appInstalled" in loading && "apps" !in errors)
                AtlasCatalogStatus(vm, "appInstalled", "已连接应用", onRefresh = { refreshApps(true) },
                    onDiagnostics = { navigate("diagnostics") })
            else AtlasCatalogStatus(vm, "apps", "应用", onRefresh = { refreshApps(true) },
                onDiagnostics = { navigate("diagnostics") })
        } else {
            if ("apps" in loading) AtlasOperationProgress("正在同步应用目录")
            if (!connected) CodexNotice("当前显示上次读取的应用。", NoticeTone.Warning,
                actionLabel = if (engineStatus is EngineStatus.Unavailable) "重新连接" else null,
                onAction = { vm.connect() })
        }
        if (connected && ("appInstalled" in errors || "apps" in errors) && apps.isNotEmpty())
            CodexNotice("部分应用状态未能更新，可继续查看已读取的信息。", NoticeTone.Warning,
                actionLabel = "重试", onAction = { refreshApps(true) })
        if (connected && "appInstalled" in errors && apps.isEmpty())
            AtlasCatalogStatus(vm, "appInstalled", "连接状态", onRefresh = { refreshApps(true) },
                onDiagnostics = { navigate("diagnostics") })
        if (apps.isNotEmpty()) {
            AtlasSearchField(appQuery, { appQuery = it }, "搜索应用", onClear = { appQuery = "" },
                modifier = Modifier.testTag("apps-search"))
            if (visible.isEmpty()) CodexEmptyState("没有匹配的应用", "修改搜索词查看其他应用。",
                actionLabel = "清除搜索", onAction = { appQuery = "" })
            for (isConnected in listOf(true, false)) {
                val items = groups[isConnected].orEmpty()
                if (items.isEmpty()) continue
                AtlasListHeading(if (isConnected) "已连接应用" else "其他应用", items.size, suffix = "项")
                MaterialPanel(Modifier.fillMaxWidth()) {
                    items.forEachIndexed { index, app ->
                        if (index > 0) HorizontalDivider(color = Ink.border.copy(alpha = .5f))
                        val id = app.optString("id")
                        val appStatus = statuses.getValue(id)
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { inspectingId = id },
                            verticalAlignment = Alignment.CenterVertically) {
                            CodexIcon(CodexGlyph.Apps, null, tint = Ink.copper, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(app.optString("name").ifBlank { "未命名应用" }, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(appStatus, color = if (appStatus == "已连接") Ink.jade else Ink.muted,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            CodexIcon(CodexGlyph.ChevronRight, "查看应用详情", tint = Ink.muted)
                        }
                    }
                }
            }
        }
        if (connected && cursor.isNotBlank()) CodexSecondaryButton(onClick = {
            val previous = directory
            vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("cursor", cursor),
                onSuccess = { next ->
                    vm.data.value = vm.data.value + ("apps" to ExtensionCatalogPolicy.appendApps(previous, next))
                })
        }, enabled = "apps" !in loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("加载更多应用")
        }
        if (apps.isEmpty() && directory != null && installed != null && loading.none { it == "apps" || it == "appInstalled" } &&
            errors["apps"] == null && errors["appInstalled"] == null)
            CodexEmptyState("暂无连接应用", "安装所需插件或检查账号授权后，可在这里管理应用。",
                actionLabel = "发现插件", onAction = { SettingsDeepLink.select("plugins", "discover"); navigate("plugins") })
    }
    apps.firstOrNull { it.optString("id") == inspectingId }?.let { app ->
        val id = app.optString("id")
        val name = app.optString("name").ifBlank { "应用" }
        val description = PresentationPolicy.meaningful(app.optString("description"))
        val installUrl = PresentationPolicy.meaningful(app.optString("installUrl"))
        val active = installedById[id]
        val enabled = app.optBoolean("isEnabled", active?.optBoolean("enabled") ?: true)
        val appStatus = statuses.getValue(id)
        CodexDetailSheet(title = name, onDismiss = { inspectingId = null }) {
            StatusPill(appStatus, appStatus == "已连接", tone = if (appStatus == "已连接") StatusTone.Success else StatusTone.Neutral)
            Text("应用说明", style = MaterialTheme.typography.titleMedium)
            Text(description.ifEmpty { "该应用暂未提供说明。" }, color = Ink.muted)
            if (installUrl.startsWith("https://")) {
                CodexPrimaryButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(installUrl)))
                        vm.extensions.appAuthorizationPending = true
                    } catch (failure: android.content.ActivityNotFoundException) {
                        vm.errors.value = vm.errors.value + ("appMutation" to "没有可打开授权页面的浏览器")
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    CodexIcon(CodexGlyph.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (appStatus == "待授权") "前往授权" else "管理应用授权")
                }
            }
            if (id.matches(Regex("[A-Za-z0-9_-]+")) && (app.has("isEnabled") || active != null))
                CodexSecondaryButton(onClick = { desired = !enabled; target = app; inspectingId = null },
                    enabled = connected && !writing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (enabled) "停用应用" else "启用应用")
                }
        }
    }
    target?.let { item ->
        val id = item.optString("id")
        CodexConfirmDialog(title = if (desired) "启用连接应用" else "停用连接应用",
            message = "此操作将修改 Codex 对该应用的本地启用配置。",
            detail = item.optString("name"), confirmLabel = "确认修改", destructive = !desired,
            onConfirm = {
                vm.rpc("appMutation", "config/value/write", JSONObject().put("keyPath", "apps." + id + ".enabled")
                    .put("value", desired).put("mergeStrategy", "replace"), after = {
                    vm.refresh("appInstalled", "app/installed", JSONObject().put("forceRefresh", true))
                    vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("forceRefetch", true))
                })
                target = null
            }, onDismiss = { target = null })
    }
}

@Composable
fun McpServicesPage(vm: CodexViewModel, navigate: (String) -> Unit = {}) {
    val data by vm.data.collectEntries("mcp")
    val loading by vm.loading.collectAsState()
    val activeThread by vm.activeThread.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    val errors by vm.errors.collectAsState()
    val usable = remember(data) { McpAvailableServices.visible(records(data["mcp"])) }

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
                    "重新连接", { vm.connect() }, { navigate("diagnostics") },
                    artworkRes = R.drawable.ic_empty_connection)
            } else if (errors["mcp"] != null || errors["mcpReload"] != null) {
                CodexRecoveryState("MCP 目录读取失败",
                    "暂未取得有效的服务目录，可以重新读取或查看诊断。",
                    "重新读取", { vm.refreshMcpServices() }, { navigate("diagnostics") })
            } else if (data["mcp"] != null) {
                CodexEmptyState(
                    "暂无可用 MCP 服务",
                    "在任务对话中要求智能体接入 MCP 服务，连通后会自动显示。",
                    icon = CodexGlyph.Hub,
                    actionLabel = "刷新服务", onAction = { vm.refreshMcpServices() }
                )
            } else {
                CodexRecoveryState("MCP 目录尚未读取",
                    "连接成功后可以重新检查服务目录。",
                    "重新读取", { vm.refreshMcpServices() }, { navigate("diagnostics") }, artworkRes = R.drawable.ic_empty_content)
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
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { details = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CodexIcon(CodexGlyph.Hub, null, tint = Ink.copper)
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
            CodexIcon(CodexGlyph.ChevronRight,
                contentDescription = "查看服务详情", tint = Ink.muted)
        }
    }
    if (details) CodexDetailSheet(
        title = McpAvailableServices.displayName(server),
        subtitle = "已就绪服务 · 由智能体管理授权和调用",
        onDismiss = { details = false }
    ) {
        StatusPill("可用", true)
        Text(server.optString("description").ifBlank {
            "服务已连接，可用能力由智能体在任务中选择调用。"
        }, style = MaterialTheme.typography.bodyMedium, color = Ink.parchment)
        Text("工具 $toolCount 项 · 资源 $resourceCount 项",
            style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        Text("此处显示已经连接的服务，具体能力由智能体在任务中使用。",
            style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}
