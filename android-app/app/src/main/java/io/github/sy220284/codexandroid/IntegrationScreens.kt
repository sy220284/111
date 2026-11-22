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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
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
    LaunchedEffect(Unit) { vm.refresh("modes", "collaborationMode/list") }
    ScreenColumn {
        SectionIntro("WORKFLOW / BEHAVIOR", "协作与工作方式", "根据任务选择执行方式。计划模式先形成方案，再由你明确推进。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("新任务使用的工作方式", style = MaterialTheme.typography.titleMedium)
            StatusPill(if (selected == "plan") "计划模式" else "标准执行模式", true)
            Text("更改后从下一轮对话生效。不会自动开启派生智能体。", color = Ink.muted)
        }
        IntegrationState(vm, "modes") { vm.refresh("modes", "collaborationMode/list") }
        listOf(
            Triple("default", "标准执行", "直接分析任务，并按允许的权限执行步骤。"),
            Triple("plan", "先制定计划", "先展示方案与关键决策，适合复杂改造和多阶段任务。")
        ).forEach { (id, name, description) ->
            val available = catalog.any { it.optString("mode") == id }
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
                        enabled = id == "default" || available)
                }
                if (id == "plan" && !available)
                    Text("当前服务未公布该模式，请刷新模型与模式目录。", color = Ink.muted)
            }
        }
        if (catalog.isNotEmpty()) {
            Text("当前核心提供的模式", style = MaterialTheme.typography.titleLarge)
            catalog.forEach { entry ->
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text(entry.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Text("模式：" + entry.optString("mode"), color = Ink.muted)
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
        SectionIntro("CONNECTORS / APPS", "已连接应用", "清楚区分发现、配置启用和当前可调用状态。")
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
                        Text(if (active?.optBoolean("callable") == true) "已经可以调用" else
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


@Composable
fun EnvironmentsPage(vm: CodexViewModel) {
    val results by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    var environmentId by remember { mutableStateOf("local") }
    var endpoint by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    ScreenColumn {
        SectionIntro("EXECUTION / ENVIRONMENT", "执行环境", "本机工作区优先；可按需明确连接兼容的外部执行服务。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = Ink.copper)
            Text("Android 本机执行", style = MaterialTheme.typography.titleMedium)
            Text("默认在 Android 应用私有工作区运行，文件仅存储在设备本地。", color = Ink.muted)
            StatusPill("默认环境", true)
            Text("当前工作区：本机工作区", color = Ink.muted,
                style = MaterialTheme.typography.labelMedium)
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("查询执行服务状态", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(environmentId, { environmentId = it }, label = { Text("环境名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    vm.refresh("envStatus", "environment/status",
                        JSONObject().put("environmentId", environmentId))
                }, enabled = environmentId.isNotBlank()) { Text("检查状态") }
                OutlinedButton(onClick = {
                    vm.refresh("envInfo", "environment/info",
                        JSONObject().put("environmentId", environmentId))
                }, enabled = environmentId.isNotBlank()) { Text("查看信息") }
            }
        }
        for (key in listOf("envStatus", "envInfo", "environmentAdd")) {
            errors[key]?.let { ErrorText(it) }
            results[key]?.let { response ->
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text(when(key) { "envStatus" -> "状态"; "envInfo" -> "环境详情"; else -> "新增结果" },
                        style = MaterialTheme.typography.titleMedium)
                    val state = PresentationPolicy.serviceState(response.optString("status"))
                    Text(if (key == "environmentAdd") "已发送环境接入请求"
                        else "运行状态：$state",
                        style = MaterialTheme.typography.bodyMedium)
                    PresentationPolicy.meaningful(response.optString("displayName")).takeIf {
                        it.isNotEmpty()
                    }?.let { Text(it, color = Ink.muted) }
                }
            }
        }
        TextButton(onClick = { showAdd = !showAdd }) {
            Icon(Icons.Outlined.AddCircleOutline, null)
            Text(if (showAdd) "收起外部环境配置" else "添加外部执行环境")
        }
        CodexReveal(showAdd) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("接入额外执行服务", style = MaterialTheme.typography.titleMedium)
                Text("仅连接已部署、可验证的执行服务。远端任务可能访问你明确选择的远程环境。", color = Ink.muted)
                OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("执行服务 WebSocket URL") },
                    placeholder = { Text("wss://example.com/exec") }, singleLine = true)
                OutlinedButton(onClick = {
                    vm.rpc("environmentAdd", "environment/add",
                        JSONObject().put("environmentId", environmentId)
                            .put("execServerUrl", endpoint),
                        after = { vm.refresh("envStatus", "environment/status",
                            JSONObject().put("environmentId", environmentId)) })
                }, enabled = environmentId.isNotBlank() &&
                    (endpoint.startsWith("wss://") || endpoint.startsWith("ws://127.0.0.1:"))) {
                    Text("添加并检查")
                }
            }
        }
    }
}
