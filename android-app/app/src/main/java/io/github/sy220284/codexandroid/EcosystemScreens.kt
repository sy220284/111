package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

@Composable
internal fun SourceState(vm: CodexViewModel, key: String, retry: () -> Unit) {
    ProtocolFeedback(vm, listOf(key), onRetry = retry)
}

@Composable
private fun DetailToggle(value: JSONObject, label: String = "查看服务信息") {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        Text(if (open) "收起详情" else label)
    }
    CodexReveal(open) {
        val description = PresentationPolicy.meaningful(value.optString("description"))
        val status = PresentationPolicy.meaningful(value.optString("status"))
        if (description.isNotEmpty()) {
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = Ink.muted)
        }
        if (status.isNotEmpty()) {
            Text("当前状态：" + PresentationPolicy.serviceState(status),
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        }
        if (description.isEmpty() && status.isEmpty())
            Text("当前没有更多可展示的服务信息。", color = Ink.muted)
    }
}

@Composable
fun ModelsPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val chosen by vm.modelId.collectAsState()
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val items = records(data["models"])
    LaunchedEffect(Unit) { vm.ensureCatalog("models", "model/list") }
    ScreenColumn {
        SectionIntro("MODELS / SELECTION", "模型目录", "直接从官方模型目录选择新会话的运行模型。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("新会话使用的模型", style = MaterialTheme.typography.titleMedium)
            Text(chosen.ifBlank { "遵循 Codex 默认模型" }, color = Ink.copper)
            Text("更改这里的选择不会中断已经启动的会话。", color = Ink.muted)
            OutlinedButton(onClick = { vm.modelId.value = "" }) { Text("恢复默认模型") }
        }
        SourceState(vm, "models") { vm.refresh("models", "model/list") }
        if (items.isEmpty() && data["models"] != null) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("暂无可显示的模型。先在账号设置中完成认证，再刷新目录。", color = Ink.muted)
            TextButton(onClick = { vm.refresh("models", "model/list") }) { Text("重新加载") }
        }
        items.filter { !it.optBoolean("hidden") }.forEach { item ->
            val id = item.optString("id")
            val title = item.optString("displayName").ifBlank { item.optString("model", id) }
            val chosenNow = chosen == id
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { vm.modelId.value = id }
                    .animateContentSize(animationSpec = tween(CodexMotion.duration(180, reduced))),
                shape = RoundedCornerShape(18.dp),
                color = Ink.canvas,
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
                        Text("思考能力：" + choices.objects().joinToString(" · ") {
                            PresentationPolicy.reasoningLabel(it.optString("reasoningEffort")) },
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
    var showCustomEditor by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var customEnvKey by remember { mutableStateOf("") }
    var confirmCustom by remember { mutableStateOf(false) }
    var showCapabilities by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("providers") == "custom") showCustomEditor = true
        vm.ensureCatalog("providers", "modelProvider/list")
    }
    ScreenColumn {
        SectionIntro("MODEL / PROVIDERS", "模型服务商", "显示服务商来源与当前默认配置。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("默认服务商", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            val defaultName = entries.firstOrNull { it.optString("id") == defaultId }
                ?.let { PresentationPolicy.meaningful(it.optString("name")) }
                .orEmpty()
            Text(defaultName.ifEmpty { "使用默认服务商" },
                style = MaterialTheme.typography.titleLarge)
            Text("不同服务商拥有独立目录与认证配置。", color = Ink.muted)
        }
        SourceState(vm, "providers") { vm.refresh("providers", "modelProvider/list") }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务商配置", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { showCustomEditor = !showCustomEditor }) {
                    Text(if (showCustomEditor) "收起" else "添加自定义服务商")
                }
            }
            CodexReveal(showCustomEditor) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(customName, { customName = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("服务商名称") },
                        singleLine = true)
                    OutlinedTextField(customUrl, { customUrl = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("服务地址（HTTP / HTTPS）") },
                        singleLine = true)
                    OutlinedTextField(customEnvKey, { customEnvKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密钥环境变量名称（可选）") },
                        singleLine = true)
                    Text("密钥值由本机环境管理；这里仅填写变量名称。",
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    OutlinedButton(
                        onClick = { confirmCustom = true },
                        enabled = customName.isNotBlank() &&
                            (customUrl.startsWith("https://") || customUrl.startsWith("http://"))
                    ) { Text("保存自定义服务商") }
                }
            }
            TextButton(onClick = {
                showCapabilities = !showCapabilities
                if (showCapabilities) vm.refresh("providerCapabilities",
                    "modelProvider/capabilities/read")
            }) { Text(if (showCapabilities) "收起能力信息" else "查看服务商能力") }
            CodexReveal(showCapabilities) {
                val caps = data["providerCapabilities"]
                if (caps != null) {
                    Text("联网搜索：" + if (caps.optBoolean("webSearch")) "支持" else "未声明",
                        color = Ink.muted)
                    Text("图片生成：" + if (caps.optBoolean("imageGeneration")) "支持" else "未声明",
                        color = Ink.muted)
                } else Text("正在读取服务能力…", color = Ink.muted)
                val errors by vm.errors.collectAsState()
                errors["providerCapabilities"]?.let { ErrorText(it) }
            }
            val errors by vm.errors.collectAsState()
            errors["providerWrite"]?.let { ErrorText(it) }
        }
        entries.forEach { item ->
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Text(PresentationPolicy.providerName(item.optString("name")),
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
                    OutlinedButton(onClick = { toSelect = item }, enabled = data["providers"]?.optBoolean("canChangeDefault") == true) {
                        Text("设为默认服务商")
                    }
                }
                DetailToggle(item)
            }
        }
        if (entries.isEmpty() && data["providers"] != null) {
            CodexEmptyState("暂无可用模型服务商", "当前内核没有返回已配置的服务商。")
        }
    }
    if (confirmCustom) CodexConfirmDialog(
        title = "保存自定义模型服务商",
        message = "会在当前内核中新增自定义网关配置。请核对地址和密钥变量名称。",
        detail = "名称：$customName\n服务地址：$customUrl\n密钥变量：$customEnvKey",
        confirmLabel = "确认保存",
        onConfirm = {
            val patch = JSONObject().put("type", "customGateway")
                .put("name", customName.trim()).put("baseUrl", customUrl.trim())
            if (customEnvKey.isNotBlank()) patch.put("envKey", customEnvKey.trim())
            vm.rpc("providerWrite", "modelProvider/config/write",
                JSONObject().put("settings", patch),
                after = {
                    vm.refresh("providers", "modelProvider/list")
                    vm.refresh("models", "model/list")
                    showCustomEditor = false
                    customName = ""; customUrl = ""; customEnvKey = ""
                })
            confirmCustom = false
        }, onDismiss = { confirmCustom = false }
    )
    toSelect?.let { item ->
        CodexConfirmDialog(
            title = "切换默认模型服务商",
            message = "将新会话的默认服务商切换为：" +
                PresentationPolicy.providerName(item.optString("name")),
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
fun PluginsPage(vm: CodexViewModel, navigate: (String) -> Unit = {}) {
    val data by vm.data.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    var installedOnly by remember { mutableStateOf(true) }
    val activeKey = if (installedOnly) "installedPlugins" else "plugins"
    val marketplaces = data[activeKey]?.optJSONArray("marketplaces")?.objects() ?: emptyList()
    val busy by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    var action by remember { mutableStateOf<Triple<JSONObject, JSONObject, Boolean>?>(null) }
    var selectedPlugin by remember { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }
    var searchDraft by rememberSaveable { mutableStateOf("") }
    var searchSubmitted by rememberSaveable { mutableStateOf("") }
    var searchPage by remember { mutableIntStateOf(0) }
    val searchActive = !installedOnly && searchSubmitted.isNotBlank()
    val submittedMatchesDraft = ThreadSearchSubmission.matches(searchDraft, searchSubmitted)
    val searchPrefix = "pluginSearch:" + searchSubmitted + ":"
    val searchKey = searchPrefix + searchPage
    val found = if (searchActive && submittedMatchesDraft) (0..searchPage)
        .flatMap { PluginSearchContract.entries(data[searchPrefix + it]) }
        .distinctBy { it.marketplace.optString("name") + ":" +
            it.plugin.optString("id").ifBlank { it.plugin.optString("name") } }
        else emptyList()
    val searchCursor = if (searchActive && submittedMatchesDraft && data[searchKey] != null)
        data[searchKey]?.optString("nextCursor").orEmpty()
    else ""
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("plugins") == "discover") installedOnly = false
    }
    LaunchedEffect(installedOnly) {
        if (installedOnly) vm.refresh("installedPlugins", "plugin/installed",
            CatalogRequestContract.workspace(vm.workingDirectory))
        else vm.refresh("plugins", "plugin/list",
            CatalogRequestContract.workspace(vm.workingDirectory))
    }
    ScreenColumn {
        SectionIntro("CODEX / MARKETPLACE", "插件",
            "先管理已安装能力，需要新功能时再浏览可用插件。")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = installedOnly, onClick = { installedOnly = true },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("plugins-tab-installed"),
                label = { Text("已安装") }
            )
            SegmentedButton(
                selected = !installedOnly, onClick = { installedOnly = false },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("plugins-tab-catalog"),
                label = { Text("发现插件") }
            )
        }
        if (!installedOnly) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("搜索插件", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("plugins-search-heading"))
                val searchAvailable = searchDraft.isNotBlank() &&
                    "pluginSearch:" + searchSubmitted + ":0" !in busy &&
                    "pluginMutation" !in busy &&
                    engineStatus == EngineStatus.Connected
                val performSearch = {
                    val term = searchDraft.trim()
                    if (term.isNotEmpty()) {
                        searchSubmitted = term
                        searchPage = 0
                        vm.refresh("pluginSearch:" + term + ":0",
                            "plugin/search", PluginSearchContract.params(term))
                    }
                }
                OutlinedTextField(
                    value = searchDraft, onValueChange = { searchDraft = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("插件名称或关键字") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (searchDraft.isNotBlank()) IconButton(onClick = {
                            searchDraft = ""; searchSubmitted = ""; searchPage = 0
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, "清除搜索词")
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { if (searchAvailable) performSearch() })
                )
                CodexPrimaryButton(
                    onClick = performSearch, enabled = searchAvailable,
                    modifier = Modifier.fillMaxWidth().testTag("plugins-search-submit")
                ) { Text("搜索插件") }
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (installedOnly) "当前安装来源" else "插件源",
                        style = MaterialTheme.typography.titleMedium)
                    Text("关联 " + marketplaces.size + " 个来源", color = Ink.muted)
                }
                IconButton(onClick = {
                    if (installedOnly) vm.refresh("installedPlugins", "plugin/installed",
                        CatalogRequestContract.workspace(vm.workingDirectory))
                    else vm.refresh("plugins", "plugin/list",
                        CatalogRequestContract.workspace(vm.workingDirectory)
                            .put("forceRefetch", true))
                }) {
                    Icon(Icons.Outlined.Refresh, "刷新插件列表")
                }
            }
        }
        ProtocolFeedback(vm, listOf("pluginMutation"))
        if ("pluginMutation" in busy) CodexNotice(
            "正在修改插件安装状态，完成后自动更新已安装列表。", NoticeTone.Info)
        SourceState(vm, activeKey) {
            if (installedOnly) vm.refresh("installedPlugins", "plugin/installed",
                CatalogRequestContract.workspace(vm.workingDirectory))
            else vm.refresh("plugins", "plugin/list",
                CatalogRequestContract.workspace(vm.workingDirectory))
        }
        if (engineStatus != EngineStatus.Connected && data[activeKey] == null) {
            CodexRecoveryState("连接后可查看插件",
                "已安装和可发现的插件由运行核心提供。",
                "重新连接", { vm.connect() }, { navigate("diagnostics") })
        } else if (engineStatus == EngineStatus.Connected &&
            data[activeKey] == null && activeKey !in busy &&
            errors[activeKey] != null) {
            CodexRecoveryState("插件目录读取失败",
                "检查连接或重试读取插件目录。",
                "重新读取", {
                    if (installedOnly) vm.refresh("installedPlugins", "plugin/installed",
                        CatalogRequestContract.workspace(vm.workingDirectory))
                    else vm.refresh("plugins", "plugin/list",
                        CatalogRequestContract.workspace(vm.workingDirectory))
                }, { navigate("diagnostics") })
        }
        if (searchActive) {
            if (!submittedMatchesDraft) CodexNotice(
                "搜索词已改变，重新搜索后显示对应结果。", NoticeTone.Info)
            if (submittedMatchesDraft) Text("插件搜索结果 · ${found.size} 项",
                style = MaterialTheme.typography.titleMedium)
            if (submittedMatchesDraft && searchKey in busy)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            if (submittedMatchesDraft) errors[searchKey]?.let {
                ErrorText("插件搜索失败：$it。可以改用常规插件目录。")
            }
            if (submittedMatchesDraft && data[searchPrefix + "0"] != null &&
                searchKey !in busy && found.isEmpty() && searchKey !in errors) {
                CodexEmptyState("没有搜索结果",
                    "该实验接口可能未启用，也可能尚无匹配插件；可以清除关键词浏览完整目录。")
            }
            found.forEach { hit ->
                val market = hit.marketplace
                val item = hit.plugin
                val display = item.optJSONObject("interface")?.optString("displayName")
                    .orEmpty().ifBlank { item.optString("name") }
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Extension, null, tint = Ink.copper)
                        Spacer(Modifier.width(9.dp))
                        Text(display, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        if (item.optBoolean("installed")) StatusPill("已安装", true)
                    }
                    Text("来源：" + market.optString("name"), color = Ink.muted,
                        style = MaterialTheme.typography.labelMedium)
                    Text(item.optJSONObject("interface")?.optString("shortDescription")
                        .orEmpty().ifBlank { item.optString("description") },
                        color = Ink.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!item.optBoolean("installed")) {
                            OutlinedButton(onClick = { action = Triple(market, item, true) },
                                enabled = item.optString("installPolicy") != "NOT_AVAILABLE" &&
                                     "pluginMutation" !in busy) {
                                Text("安装插件")
                            }
                        }
                        TextButton(onClick = { selectedPlugin = market to item },
                         enabled = "pluginMutation" !in busy) {
                            Text("查看详情")
                        }
                    }
                }
            }
            if (searchCursor.isNotBlank()) {
                OutlinedButton(onClick = {
                    val next = searchPage + 1
                    searchPage = next
                    vm.refresh(searchPrefix + next, "plugin/search",
                        PluginSearchContract.params(searchSubmitted, searchCursor))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = searchKey !in busy && "pluginMutation" !in busy) {
                    Text("加载更多结果")
                }
            }
        }
        if (!searchActive) marketplaces.forEach { market ->
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
                            OutlinedButton(onClick = { action = Triple(market, item, false) },
                                enabled = "pluginMutation" !in busy) { Text("卸载") }
                        } else {
                            OutlinedButton(onClick = { action = Triple(market, item, true) },
                                enabled = item.optString("installPolicy") != "NOT_AVAILABLE" &&
                                     "pluginMutation" !in busy) { Text("安装插件") }
                        }
                    }
                    TextButton(onClick = { selectedPlugin = market to item },
                        enabled = "pluginMutation" !in busy,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Info, null)
                        Spacer(Modifier.width(6.dp))
                        Text("查看插件详情")
                    }
                }
            }
        }
        if (!searchActive && data[activeKey] != null && marketplaces.isEmpty() && activeKey !in busy && activeKey !in errors) {
            CodexEmptyState(
                if (installedOnly) "暂未发现已安装插件" else "没有可用插件市场",
                if (installedOnly) "从插件市场安装后，可在这里集中管理。"
                else "检查 Codex 核心连接，或刷新市场目录。",
                actionLabel = if (installedOnly) "浏览插件" else "刷新目录",
                onAction = {
                    if (installedOnly) installedOnly = false
                    else vm.refresh("plugins", "plugin/list", JSONObject().put("forceRefetch", true))
                }
            )
        }
    }
    selectedPlugin?.let { (market, item) ->
        PluginDetailSheet(marketplace = market, entry = item, vm = vm,
            onDismiss = { selectedPlugin = null })
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
                    vm.rpc("pluginMutation", "plugin/install", params,
                        after = {
                            vm.refresh("plugins", "plugin/list")
                            vm.refresh("installedPlugins", "plugin/installed",
                                CatalogRequestContract.workspace(vm.workingDirectory))
                        })
                } else {
                    vm.rpc("pluginMutation", "plugin/uninstall", JSONObject().put("pluginId", item.optString("id")),
                        after = {
                            vm.refresh("plugins", "plugin/list")
                            vm.refresh("installedPlugins", "plugin/installed",
                                CatalogRequestContract.workspace(vm.workingDirectory))
                        })
                }
                action = null
            },
            onDismiss = { action = null }
        )
    }
}

@Composable
fun EngineDiagnosticsPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsState()
    val errors by vm.errors.collectAsState()
    val data by vm.data.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    var showRecoverySteps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("diagnostics") == "logs") showLogs = true
    }
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val work = observeGlobalWorkSnapshot(vm)
    var showCrashes by remember { mutableStateOf(false) }
    var crashRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    ScreenColumn {
        SectionIntro("ENGINE / DIAGNOSTICS", "内核诊断", "查看真实运行状态与官方诊断接口结果。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Memory, null, tint = Ink.copper)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("本机运行服务", style = MaterialTheme.typography.titleMedium)
                    StatusPill(if (status == EngineStatus.Connected) "已连接" else "连接异常", status == EngineStatus.Connected)
                }
            }
            val statusSnapshot = status
            Text(when (statusSnapshot) {
                EngineStatus.Starting -> "正在启动运行服务。"
                EngineStatus.Connected -> "运行服务正常，可以继续处理任务。"
                is EngineStatus.Unavailable ->
                    "本机内核连接失败，可尝试重新连接或查看启动日志。"
            }, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            DividerLine()
            val stderr by vm.bridge.recentStderr.collectAsState()
            if (stderr.isNotEmpty()) {
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(if (showLogs) "收起启动日志" else "查看启动日志")
                }
                CodexReveal(showLogs) {
                    Text(stderr.joinToString("\n"), color = Ink.muted,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.connectionRestarter.restart() },
                    enabled = !restarting && status != EngineStatus.Starting &&
                        ConnectionRestartPolicy.canRestart(work),
                    modifier = Modifier.testTag("diagnostics-restart")
                        .explainDisabled(
                            restarting || !ConnectionRestartPolicy.canRestart(work),
                            "有任务或待处理的审批，先完成后再重启内核")
                ) {
                    Icon(Icons.Outlined.RestartAlt, null)
                    Text(if (restarting) "正在重新连接…" else "重启连接")
                }
                OutlinedButton(onClick = { vm.refresh("diagnostics", "server/diagnostics") },
                    enabled = status == EngineStatus.Connected) { Text("检查运行状况") }
            }
            if (!work.canDisruptCoreOrData) {
                Text("运行期间保留当前内核进程，避免中断正在执行的任务。",
                    color = Ink.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (status == EngineStatus.Connected) {
            PresentationPolicy.visibleErrors(errors, listOf("diagnostics", "engine"))
                .forEach { ErrorText(it) }
        }
        data["diagnostics"]?.let {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("诊断结果", style = MaterialTheme.typography.titleMedium)
                DetailToggle(it, "查看诊断摘要")
            }
        }
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-crash-history")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本机崩溃记录", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { showCrashes = !showCrashes }) {
                    Text(if (showCrashes) "收起" else "查看")
                }
            }
            Text("仅保存异常类型、时间和代码调用位置；不会上传执行内容。",
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            if (showCrashes) {
                val stored = remember(showCrashes, crashRevision) {
                    LocalCrashReporter.read(context)
                }
                if (stored.isBlank()) {
                    Text("尚无本机崩溃记录", color = Ink.muted)
                } else {
                    SelectionContainer {
                        Text(stored, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    }
                    TextButton(onClick = {
                        LocalCrashReporter.clear(context)
                        crashRevision++
                    }) { Text("清除本机崩溃记录", color = Ink.red) }
                }
            }
        }
        if (status is EngineStatus.Unavailable) {
            TextButton(onClick = { showRecoverySteps = !showRecoverySteps }) {
                Text(if (showRecoverySteps) "收起排查步骤" else "查看排查步骤")
            }
            CodexReveal(showRecoverySteps) {
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text("先重新连接运行服务；再核实账号登录状态和失败的功能。若仍无法使用，可查看启动日志。",
                        color = Ink.muted)
                }
            }
        }
    }
}

