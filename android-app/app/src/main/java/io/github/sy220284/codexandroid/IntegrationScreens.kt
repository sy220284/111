package io.github.sy220284.codexandroid

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
        SectionIntro("WORKFLOW / BEHAVIOR", "协作与工作方式", "根据任务选择执行方式。计划模式先形成方案，再由你明确推进。")
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

@Composable
fun ConnectedAppsPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val data by vm.data.collectAsState()
    val apps = records(data["apps"])
    val installed = data["appInstalled"]?.optJSONArray("apps")
    var target by remember { mutableStateOf<JSONObject?>(null) }
    var desired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.refresh("apps", "app/list", JSONObject().put("limit", 100))
        vm.refresh("appInstalled", "app/installed", JSONObject())
    }
    ScreenColumn {
        SectionIntro("CONNECTORS / APPS", "已连接应用", "查看服务连接与授权；具体工具由智能体在任务中使用。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("应用目录", style = MaterialTheme.typography.titleMedium)
            Text(if (data["apps"] != null) "已发现 ${apps.size} 个应用" else "等待同步可用应用", color = Ink.copper)
            Text("应用登录、授权与可用能力由当前连接状态决定。", color = Ink.muted)
            OutlinedButton(onClick = {
                vm.refresh("apps", "app/list", JSONObject().put("limit", 100).put("forceRefetch", true))
                vm.refresh("appInstalled", "app/installed")
            }) { Icon(Icons.Outlined.Refresh, null); Text("刷新连接状态") }
        }
        ProtocolFeedback(vm, listOf("apps", "appInstalled"), onRetry = {
            vm.refresh("apps", "app/list", JSONObject().put("limit", 100))
            vm.refresh("appInstalled", "app/installed")
        })
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
            val installUrl = app.optString("installUrl")
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(15.dp), color = Ink.canvas,
                        border = BorderStroke(1.dp, Ink.copperDim)) {
                        Icon(Icons.Outlined.Apps, null, tint = Ink.copper,
                            modifier = Modifier.padding(12.dp).size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(if (active?.optBoolean("callable") == true) "可供智能体使用" else
                            if (active?.optBoolean("enabled") == true) "已启用，等待工具就绪" else
                            if (app.optBoolean("isAccessible")) "可访问" else "尚未授权或不可用",
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    StatusPill(if (app.optBoolean("isEnabled", true)) "已启用" else "已禁用",
                        app.optBoolean("isEnabled", true))
                }
                PresentationPolicy.meaningful(app.optString("description")).takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Ink.muted)
                }
                if (installUrl.startsWith("https://")) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(installUrl)))
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("管理应用授权")
                    }
                }
                if (id.matches(Regex("[A-Za-z0-9_-]+"))) {
                    val enable = !app.optBoolean("isEnabled", true)
                    TextButton(onClick = { desired = enable; target = app }) {
                        Text(if (enable) "启用应用" else "停用应用")
                    }
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
fun McpServicesPage(vm: CodexViewModel) {
    val context = LocalContext.current
    val data by vm.data.collectAsState()
    val servers = records(data["mcp"])
    val login = data["mcpLogin"]?.optString("authorizationUrl").orEmpty()
    var confirmLogin by remember { mutableStateOf("") }
    val currentThread by vm.activeThread.collectAsState()
    fun refreshMcpServices() {
        vm.refresh("mcp", "mcpServerStatus/list",
            CatalogRequestContract.mcpStatus(currentThread))
    }
    LaunchedEffect(currentThread) { refreshMcpServices() }
    ScreenColumn {
        SectionIntro("EXTENSIONS / MCP", "MCP 服务",
            "管理扩展服务的连接与授权。工具由智能体在任务中按需调用。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已连接的服务", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text(servers.count {
                    PresentationPolicy.serviceState(it.opt("runtimeStatus")?.toString()) == "已连接"
                }.toString() + " / " + servers.size,
                    style = MaterialTheme.typography.titleMedium)
            }
            Text("只有服务连接或授权需要你操作；工具运行结果可在对应任务中查看。",
                color = Ink.muted)
            OutlinedButton(onClick = { refreshMcpServices() },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("刷新连接状态")
            }
        }
        IntegrationState(vm, "mcp") { refreshMcpServices() }
        if (servers.isEmpty() && data["mcp"] != null) {
            CodexEmptyState("尚未连接扩展服务",
                "可以在引擎配置中添加 MCP 服务，或安装提供服务的插件。",
                icon = Icons.Outlined.Hub)
        }
        servers.forEach { server ->
            McpServiceStatusCard(server, onAuthorize = { confirmLogin = it })
        }
        if (login.startsWith("https://") || login.startsWith("http://127.0.0.1")) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("完成扩展服务授权", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login)))
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("在浏览器中继续")
                }
            }
        }
        ProtocolFeedback(vm, listOf("mcpLogin"))
    }
    if (confirmLogin.isNotBlank()) CodexConfirmDialog(
        title = "授权 MCP 服务",
        message = "将为选定的 MCP 服务发起独立的 OAuth 授权流程。",
        detail = confirmLogin,
        confirmLabel = "发起授权",
        onConfirm = {
            vm.rpc("mcpLogin", "mcpServer/oauth/login",
                JSONObject().put("name", confirmLogin))
            confirmLogin = ""
        },
        onDismiss = { confirmLogin = "" }
    )
}

/** 用户只管理服务连接及授权，工具/资源的底层调用交给任务中的智能体。 */
@Composable
internal fun McpServiceStatusCard(
    server: JSONObject,
    onAuthorize: (String) -> Unit
) {
    val name = PresentationPolicy.meaningful(server.optString("name"))
        .ifEmpty { "未命名服务" }
    val status = PresentationPolicy.serviceState(server.opt("runtimeStatus")?.toString())
    val auth = PresentationPolicy.authorizationState(server.opt("authStatus")?.toString())
    MaterialPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Hub, null, tint = Ink.copper)
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), maxLines = 2)
            StatusPill(status, status == "已连接")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("授权：" + auth, color = Ink.muted, modifier = Modifier.weight(1f))
            if (auth != "已授权") {
                TextButton(onClick = { onAuthorize(name) },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("授权登录")
                }
            }
        }
        if (server.optString("toolsError").isNotBlank()) {
            Text("部分工具暂不可用，请检查服务连接或配置。",
                color = Ink.red, style = MaterialTheme.typography.bodySmall)
        }
    }
}


/**
 * One decision at a time: choose the execution location before seeing a connection form.
 * Remote environments use the existing official environment/add, info and status RPCs.
 */
@Composable
fun EnvironmentsPage(vm: CodexViewModel) {
    val results by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var useRemote by rememberSaveable { mutableStateOf(false) }
    var remoteName by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var queriedEnvironment by remember { mutableStateOf<String?>(null) }
    var lastSubmittedRemote by remember { mutableStateOf<String?>(null) }
    val targetId = if (useRemote) remoteName.trim() else "local"
    val canConnect = remoteName.isNotBlank() &&
        (endpoint.trim().startsWith("wss://") ||
            endpoint.trim().startsWith("ws://127.0.0.1:"))
    val statusBusy = "envStatus" in loading
    val infoBusy = "envInfo" in loading
    val writeBusy = "environmentAdd" in loading

    fun checkEnvironment(id: String) {
        queriedEnvironment = id
        vm.refresh("envStatus", "environment/status", JSONObject().put("environmentId", id))
    }
    fun loadEnvironmentInfo(id: String) {
        queriedEnvironment = id
        vm.refresh("envInfo", "environment/info", JSONObject().put("environmentId", id))
    }

    ScreenColumn {
        SectionIntro("EXECUTION / ENVIRONMENT", "执行环境",
            "选择工作在这台手机上完成，或连接已部署的外部执行服务。")
        Text("在哪里执行任务", style = MaterialTheme.typography.titleLarge)
        Text("先选择运行位置，需要连接时再填写配置。", color = Ink.muted)
        OutlinedButton(
            onClick = { useRemote = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-local-option")
        ) {
            Icon(Icons.Outlined.PhoneAndroid, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("在这台手机上执行", style = MaterialTheme.typography.titleMedium)
                Text("使用应用私有工作区", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
            if (!useRemote) Icon(Icons.Outlined.CheckCircle, "已选择")
        }
        OutlinedButton(
            onClick = { useRemote = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-remote-option")
        ) {
            Icon(Icons.Outlined.Computer, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("连接其他设备", style = MaterialTheme.typography.titleMedium)
                Text("使用兼容的外部执行服务", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
            if (useRemote) Icon(Icons.Outlined.CheckCircle, "已选择")
        }

        if (!useRemote) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Ink.jade)
                    Spacer(Modifier.width(8.dp))
                    Text("已选择本机工作区", style = MaterialTheme.typography.titleMedium)
                }
                Text("本机工作区文件保存在此应用的私有目录。",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                Text("需要检查本机环境服务时，可打开下方详细诊断。",
                    style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
        } else {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("连接外部执行服务", style = MaterialTheme.typography.titleMedium)
                Text("需要预先在另一台设备部署兼容的执行服务，并获得连接地址。",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                OutlinedTextField(
                    value = remoteName, onValueChange = { remoteName = it },
                    label = { Text("连接名称") },
                    placeholder = { Text("例如：工作电脑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("environment-remote-name")
                )
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("安全连接地址（WebSocket）") },
                    placeholder = { Text("wss://example.com/exec") },
                    supportingText = {
                        Text("使用 wss:// 加密连接；本机回环地址可使用 ws://127.0.0.1")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("environment-endpoint")
                )
                OutlinedButton(
                    onClick = {
                        val id = remoteName.trim()
                        val url = endpoint.trim()
                        lastSubmittedRemote = id
                        queriedEnvironment = id
                        vm.rpc("environmentAdd", "environment/add",
                            JSONObject().put("environmentId", id).put("execServerUrl", url),
                            after = { checkEnvironment(id) })
                    },
                    enabled = canConnect && !writeBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("environment-connect")
                ) {
                    Icon(Icons.Outlined.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (writeBusy) "正在提交连接" else "添加并检查连接")
                }
                if (endpoint.isNotBlank() && !canConnect) {
                    Text("请填写连接名称和安全的服务地址，才能提交连接请求。",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                if (results["environmentAdd"] != null && lastSubmittedRemote == targetId)
                    CodexNotice("连接请求已被受理，正在读取执行服务状态。", NoticeTone.Info)
            }
        }

        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.testTag("environment-advanced-toggle")
        ) {
            Icon(Icons.Outlined.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showAdvanced) "收起详细诊断" else "查看环境状态与详细诊断")
        }
        CodexReveal(showAdvanced) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("环境检查", style = MaterialTheme.typography.titleMedium)
                Text("当前环境：${if (useRemote) remoteName.ifBlank { "请先填写连接名称" } else "本机"}",
                    color = Ink.muted)
                if (!useRemote) Text("本机服务标识：local", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { checkEnvironment(targetId) },
                        enabled = targetId.isNotBlank() && !statusBusy,
                        modifier = Modifier.testTag("environment-check-status")) {
                        Text(if (statusBusy) "查询中" else "检查状态")
                    }
                    OutlinedButton(onClick = { loadEnvironmentInfo(targetId) },
                        enabled = targetId.isNotBlank() && !infoBusy,
                        modifier = Modifier.testTag("environment-info")) {
                        Text(if (infoBusy) "读取中" else "查看详情")
                    }
                }
            }
        }
        val visibleKeys = if (useRemote && lastSubmittedRemote == targetId)
            listOf("envStatus", "envInfo", "environmentAdd")
        else listOf("envStatus", "envInfo")
        if (queriedEnvironment == targetId || (useRemote && lastSubmittedRemote == targetId)) {
            PresentationPolicy.visibleErrors(errors, visibleKeys)
                .forEach { error -> ErrorText(error) }
        }
        for (key in listOf("envStatus", "envInfo")) {
            if (queriedEnvironment == targetId) {
                results[key]?.let { response ->
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Text(if (key == "envStatus") "环境运行状态" else "环境详情",
                            style = MaterialTheme.typography.titleMedium)
                        val state = PresentationPolicy.serviceState(response.optString("status"))
                        if (key == "envStatus") {
                            StatusPill(state, state == "已连接")
                        }
                        PresentationPolicy.meaningful(response.optString("displayName"))
                            .takeIf { it.isNotEmpty() }
                            ?.let { Text(it, color = Ink.muted) }
                        if (key == "envInfo")
                            Text("详细状态已读取，可在服务端查看配置与运行日志。",
                                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
