package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

@Composable
private fun SourceState(vm: CodexViewModel, key: String, retry: () -> Unit) {
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    errors[key]?.let { ErrorText(it) }
    if (key in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (key !in errors && key !in loading) {
        // Each caller also supplies its real empty-state message when data is absent.
    }
}

@Composable
private fun DetailToggle(value: JSONObject, label: String = "查看协议详情") {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        Text(if (open) "收起详情" else label)
    }
    CodexReveal(open) {
        Text(value.toString(2), color = Ink.muted,
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ModelsPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val chosen by vm.modelId.collectAsState()
    val items = records(data["models"])
    LaunchedEffect(Unit) { vm.refresh("models", "model/list") }
    ScreenColumn {
        SectionIntro("MODELS / SELECTION", "模型目录", "直接从官方模型目录选择新会话的运行模型。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("新会话使用的模型", style = MaterialTheme.typography.titleMedium)
            Text(chosen.ifBlank { "遵循 Codex 默认模型" }, color = Ink.copper)
            Text("更改这里的选择不会中断已经启动的会话。", color = Ink.muted)
            OutlinedButton(onClick = { vm.modelId.value = "" }) { Text("恢复默认模型") }
        }
        SourceState(vm, "models") { vm.refresh("models", "model/list") }
        if (items.isEmpty()) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("暂无可显示的模型。先在账号设置中完成认证，再刷新目录。", color = Ink.muted)
            TextButton(onClick = { vm.refresh("models", "model/list") }) { Text("重新加载") }
        }
        items.filter { !it.optBoolean("hidden") }.forEach { item ->
            val id = item.optString("id")
            val title = item.optString("displayName").ifBlank { item.optString("model", id) }
            val chosenNow = chosen == id
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { vm.modelId.value = id }.animateContentSize(),
                shape = RoundedCornerShape(18.dp),
                color = if (chosenNow) Ink.copper.copy(alpha = .14f) else Ink.panel,
                border = BorderStroke(1.dp, if (chosenNow) Ink.copper else Ink.border)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = if (chosenNow) Ink.copper else Ink.muted)
                        Spacer(Modifier.width(11.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        RadioButton(chosenNow, onClick = { vm.modelId.value = id })
                    }
                    Text(item.optString("description"), color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (item.optBoolean("isDefault")) StatusPill("官方默认")
                        if (chosenNow) StatusPill("已选择")
                    }
                    val choices = item.optJSONArray("supportedReasoningEfforts")
                    if (choices != null && choices.length() > 0) {
                        Text("思考能力：" + choices.objects().joinToString(" · ") { it.optString("reasoningEffort") },
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun ProvidersPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val entries = records(data["providers"])
    val defaultId = data["providers"]?.optString("defaultProviderId") ?: ""
    var toSelect by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { vm.refresh("providers", "modelProvider/list") }
    ScreenColumn {
        SectionIntro("MODEL / PROVIDERS", "模型服务商", "显示服务商来源与当前默认配置。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("默认服务商", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            Text(defaultId.ifBlank { "等待加载" }, style = MaterialTheme.typography.titleLarge)
            Text("不同服务商拥有独立目录与认证配置。", color = Ink.muted)
        }
        SourceState(vm, "providers") { vm.refresh("providers", "modelProvider/list") }
        entries.forEach { item ->
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Text(item.optString("name").ifBlank { item.optString("id") },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (defaultId == item.optString("id")) StatusPill("当前默认")
                }
                item.optString("baseUrl").takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                }
                if (item.optBoolean("canEdit")) {
                    Text("可在原生配置页修改具体参数。", color = Ink.muted)
                }
                if (defaultId != item.optString("id")) {
                    Button(onClick = { toSelect = item }, enabled = data["providers"]?.optBoolean("canChangeDefault") == true) {
                        Text("设为默认服务商")
                    }
                }
                DetailToggle(item)
            }
        }
        if (entries.isEmpty()) MaterialPanel(Modifier.fillMaxWidth()) { Text("未取得模型服务商列表。", color = Ink.muted) }
    }
    toSelect?.let { item ->
        CodexConfirmDialog(
            title = "切换默认模型服务商",
            message = "将新会话的默认服务商切换为：" + item.optString("name", item.optString("id")),
            confirmLabel = "确认切换",
            onConfirm = {
                vm.rpc("providers", "modelProvider/default/set",
                    JSONObject().put("providerId", item.optString("id")),
                    after = { vm.refresh("providers", "modelProvider/list"); vm.refresh("models", "model/list") })
                toSelect = null
            },
            onDismiss = { toSelect = null }
        )
    }
}

@Composable
fun SkillsPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val groups = records(data["skills"])
    var confirmation by remember { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }
    LaunchedEffect(Unit) { vm.refresh("skills", "skills/list", JSONObject().put("cwds", JSONArray().put(vm.workingDirectory))) }
    ScreenColumn {
        SectionIntro("EXTENSIONS / SKILLS", "技能", "从 Codex 原生目录读取技能，按技能路径单独启用或停用。")
        SourceState(vm, "skills") { vm.refresh("skills", "skills/list") }
        groups.forEach { group ->
            Text(group.optString("cwd"), color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            group.optJSONArray("skills")?.objects()?.forEach { skill ->
                val meta = skill.optJSONObject("interface") ?: JSONObject()
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.School, null, tint = Ink.copper)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(meta.optString("displayName").ifBlank { skill.optString("name") },
                                style = MaterialTheme.typography.titleMedium)
                            Text(skill.optString("path"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        }
                        Switch(checked = skill.optBoolean("enabled"), onCheckedChange = { enabled ->
                            confirmation = skill to enabled
                        })
                    }
                    Text(meta.optString("shortDescription").ifBlank { skill.optString("description") },
                        color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                    DetailToggle(skill)
                }
            }
            group.optJSONArray("errors")?.objects()?.forEach { issue ->
                ErrorText(issue.toString())
            }
        }
        if (groups.isEmpty()) MaterialPanel(Modifier.fillMaxWidth()) { Text("目录中暂时没有技能。", color = Ink.muted) }
    }
    confirmation?.let { (skill, enabled) ->
        CodexConfirmDialog(
            title = if (enabled) "启用技能" else "停用技能",
            message = "将修改此技能在 Codex 中的启用状态。",
            detail = "技能：" + skill.optString("name") + "\n路径：" + skill.optString("path"),
            confirmLabel = if (enabled) "确认启用" else "确认停用",
            onConfirm = {
                vm.rpc("skills", "skills/config/write",
                    JSONObject().put("path", skill.optString("path")).put("enabled", enabled),
                    after = { vm.refresh("skills", "skills/list",
                        JSONObject().put("cwds", JSONArray().put(vm.workingDirectory))) })
                confirmation = null
            },
            onDismiss = { confirmation = null }
        )
    }
}

@Composable
fun PluginsPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val marketplaces = data["plugins"]?.optJSONArray("marketplaces")?.objects() ?: emptyList()
    var action by remember { mutableStateOf<Triple<JSONObject, JSONObject, Boolean>?>(null) }
    LaunchedEffect(Unit) { vm.refresh("plugins", "plugin/list") }
    ScreenColumn {
        SectionIntro("CODEX / MARKETPLACE", "插件", "浏览官方与本地插件市场。安装、卸载逐项确认。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("插件源", style = MaterialTheme.typography.titleMedium)
                    Text("发现 " + marketplaces.size + " 个市场", color = Ink.muted)
                }
                IconButton(onClick = { vm.refresh("plugins", "plugin/list", JSONObject().put("forceRefetch", true)) }) {
                    Icon(Icons.Outlined.Refresh, "刷新插件市场")
                }
            }
        }
        SourceState(vm, "plugins") { vm.refresh("plugins", "plugin/list") }
        marketplaces.forEach { market ->
            val plugins = market.optJSONArray("plugins")?.objects() ?: emptyList()
            Text(market.optJSONObject("interface")?.optString("displayName")
                ?.ifBlank { market.optString("name") } ?: market.optString("name"),
                style = MaterialTheme.typography.titleLarge)
            plugins.forEach { item ->
                val meta = item.optJSONObject("interface") ?: JSONObject()
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Extension, null, tint = Ink.copper)
                        Spacer(Modifier.width(11.dp))
                        Text(meta.optString("displayName").ifBlank { item.optString("name") },
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill(if (item.optBoolean("installed")) "已安装" else "可发现", item.optBoolean("enabled"))
                    }
                    Text(meta.optString("shortDescription"), color = Ink.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.optBoolean("installed")) {
                            OutlinedButton(onClick = { action = Triple(market, item, false) }) { Text("卸载") }
                        } else {
                            Button(onClick = { action = Triple(market, item, true) },
                                enabled = item.optString("installPolicy") != "NOT_AVAILABLE") { Text("安装插件") }
                        }
                    }
                    DetailToggle(item)
                }
            }
        }
        if (marketplaces.isEmpty()) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("当前没有已加载的插件市场。", color = Ink.muted)
            TextButton(onClick = { vm.refresh("plugins", "plugin/list", JSONObject().put("forceRefetch", true)) }) {
                Text("重新获取")
            }
        }
    }
    action?.let { (market, item, install) ->
        CodexConfirmDialog(
            title = if (install) "安装插件" else "卸载插件",
            message = if (install) "即将从插件市场安装此插件。" else "卸载会移除当前插件。",
            detail = item.optString("name") + "\n来源：" + market.optString("name"),
            confirmLabel = if (install) "确认安装" else "确认卸载",
            destructive = !install,
            onConfirm = {
                if (install) {
                    val params = JSONObject().put("pluginName", item.optString("name"))
                    val path = market.optString("path")
                    if (path.isNotBlank()) params.put("marketplacePath", path)
                    else params.put("remoteMarketplaceName", market.optString("name"))
                    vm.rpc("plugins", "plugin/install", params,
                        after = { vm.refresh("plugins", "plugin/list") })
                } else {
                    vm.rpc("plugins", "plugin/uninstall", JSONObject().put("pluginId", item.optString("id")),
                        after = { vm.refresh("plugins", "plugin/list") })
                }
                action = null
            },
            onDismiss = { action = null }
        )
    }
}

@Composable
fun HooksPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val groups = records(data["hooks"])
    LaunchedEffect(Unit) {
        vm.refresh("hooks", "hooks/list", JSONObject().put("cwds", JSONArray().put(vm.workingDirectory)))
    }
    ScreenColumn {
        SectionIntro("RUNTIME / HOOKS", "触发钩子", "查看执行时机、来源、权限与托管状态。")
        SourceState(vm, "hooks") { vm.refresh("hooks", "hooks/list") }
        groups.forEach { group ->
            Text(group.optString("cwd"), color = Ink.muted)
            group.optJSONArray("hooks")?.objects()?.forEach { hook ->
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bolt, null, tint = Ink.copper)
                        Spacer(Modifier.width(11.dp))
                        Text(hook.optString("eventName"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill(if (hook.optBoolean("enabled")) "启用" else "停用", hook.optBoolean("enabled"))
                    }
                    Text(hook.optString("key"), color = Ink.parchment)
                    Text("来源：" + hook.optString("sourcePath"), color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    Text("可信状态：" + hook.optString("trustStatus"), color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    DetailToggle(hook)
                }
            }
        }
        if (groups.isEmpty()) MaterialPanel(Modifier.fillMaxWidth()) { Text("尚未载入运行钩子。", color = Ink.muted) }
    }
}

@Composable
fun EngineDiagnosticsPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsState()
    val errors by vm.errors.collectAsState()
    val data by vm.data.collectAsState()
    ScreenColumn {
        SectionIntro("ENGINE / DIAGNOSTICS", "内核诊断", "查看真实运行状态与官方诊断接口结果。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Memory, null, tint = Ink.copper)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Codex App Server", style = MaterialTheme.typography.titleMedium)
                    StatusPill(if (status == EngineStatus.Connected) "已连接" else "连接异常", status == EngineStatus.Connected)
                }
            }
            val statusSnapshot = status
            Text(when (statusSnapshot) {
                EngineStatus.Starting -> "正在初始化本机进程和 JSON-RPC。"
                EngineStatus.Connected -> "进程已通过 initialize 并建立响应通道。"
                is EngineStatus.Unavailable -> statusSnapshot.reason
            }, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            DividerLine()
            Text("工作目录：" + vm.workingDirectory, fontFamily = FontFamily.Monospace, color = Ink.muted)
            val stderr by vm.bridge.recentStderr.collectAsState()
            if (stderr.isNotEmpty()) {
                Text("内核启动日志（仅保存最近 16 行）", color = Ink.copper,
                    style = MaterialTheme.typography.titleMedium)
                Text(stderr.joinToString("\n"), color = Ink.muted, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.bridge.close(); vm.connect() }) {
                    Icon(Icons.Outlined.RestartAlt, null); Text("重启连接")
                }
                Button(onClick = { vm.refresh("diagnostics", "server/diagnostics") },
                    enabled = status == EngineStatus.Connected) { Text("读取官方诊断") }
            }
        }
        errors["diagnostics"]?.let { ErrorText(it) }
        errors["engine"]?.let { ErrorText(it) }
        data["diagnostics"]?.let {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("诊断结果", style = MaterialTheme.typography.titleMedium)
                DetailToggle(it, "展开诊断数据")
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("故障排查顺序", style = MaterialTheme.typography.titleMedium)
            Text("1. 核心 ELF 是否已安装和可执行\n2. initialize 是否返回\n3. 登录状态是否有效\n4. 对应能力是否支持 Android 环境",
                color = Ink.muted)
        }
    }
}

@Composable
fun MetricsPage(id: String, vm: CodexViewModel) {
    val page = pages.first { it.id == id }
    val payload by vm.data.collectAsState()
    val result = payload[id]
    LaunchedEffect(id) { page.rpc?.let { vm.refresh(id, it) } }
    ScreenColumn {
        SectionIntro("ACCOUNT / TELEMETRY", page.title, page.subtitle)
        SourceState(vm, id) { page.rpc?.let { vm.refresh(id, it) } }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Icon(page.icon, null, tint = Ink.copper, modifier = Modifier.size(29.dp))
            Text("来源：Codex 当前账号", style = MaterialTheme.typography.titleMedium)
            Text("额度与时间窗口采用服务端返回值，未登录时可在账号页查看原因。", color = Ink.muted)
            OutlinedButton(onClick = { page.rpc?.let { vm.refresh(id, it) } }) { Text("刷新数据") }
        }
        if (result != null) {
            val keys = result.keys().asSequence().toList()
            keys.forEach { key ->
                val value = result.opt(key)
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text(key, color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                    when (value) {
                        is JSONObject -> DetailToggle(value, "展开 " + key)
                        is JSONArray -> Text(value.toString(2), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                        else -> Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else MaterialPanel(Modifier.fillMaxWidth()) { Text("等待服务端返回统计信息。", color = Ink.muted) }
    }
}
