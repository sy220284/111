package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val description = PresentationPolicy.meaningful(value.optString("description"))
    val status = PresentationPolicy.meaningful(value.optString("status"))
    val address = PresentationPolicy.meaningful(value.optString("baseUrl"))
    CodexDetailTrigger(label, onClick = { open = true })
    if (open) CodexDetailSheet(
        title = label.removePrefix("查看").ifBlank { "服务信息" },
        onDismiss = { open = false }
    ) {
        if (description.isNotEmpty()) Text(description,
            style = MaterialTheme.typography.bodyMedium, color = Ink.parchment)
        if (status.isNotEmpty()) Text("当前状态：" +
            PresentationPolicy.serviceState(status), color = Ink.muted)
        if (address.isNotEmpty()) Text("服务地址：" + address,
            color = Ink.muted)
        if (value.optBoolean("canEdit"))
            Text("可在运行核心高级配置中修改具体参数。", color = Ink.muted)
        if (description.isEmpty() && status.isEmpty() && address.isEmpty())
            CodexEmptyState("暂无更多信息", "服务已返回当前可以展示的全部内容。")
    }
}

@Composable
fun ModelsPage(vm: CodexViewModel) {
    val target = remember { SettingsDeepLink.take("models") }
    val data by vm.data.collectEntries("models")
    val modelData = data["models"]
    val items = remember(modelData) { records(modelData).filterNot { it.optBoolean("hidden") } }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        SectionIntro("模型设置", "设置后续任务的默认模型与推理方式；对话中的快速模型选择仍用于任务运行。")
        EngineSettingsSection(vm, "models", target)
        CodexDetailTrigger("查看可用模型说明", onClick = { showCatalog = true },
            modifier = Modifier.fillMaxWidth().testTag("model-catalog-info"))
    }
    if (showCatalog) CodexDetailSheet(title = "可用模型说明", onDismiss = { showCatalog = false }) {
        if (items.isEmpty()) Text("暂无可用模型，请检查账号和服务商设置。", color = Ink.muted)
        items.forEach { item ->
            val id = item.optString("id").ifBlank { item.optString("model") }
            val title = item.optString("displayName").ifBlank { id }
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (item.optBoolean("isDefault")) StatusPill("服务默认")
                val description = PresentationPolicy.meaningful(item.optString("description"))
                if (description.isNotBlank()) Text(description, color = Ink.muted)
                val efforts = item.optJSONArray("supportedReasoningEfforts")
                if (efforts != null && efforts.length() > 0) Text("思考能力：" +
                    efforts.objects().joinToString(" · ") { PresentationPolicy.reasoningLabel(it.optString("reasoningEffort")) },
                    color = Ink.muted)
            }
        }
    }
}

@Composable
fun ProvidersPage(vm: CodexViewModel) {
    val data by vm.data.collectEntries("providers", "providerCapabilities")
    val providerData = data["providers"]
    val entries = remember(providerData) { records(providerData) }
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
        SectionIntro("模型服务商", "显示服务商来源与当前默认配置。")
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
            Text("服务商配置", style = MaterialTheme.typography.titleMedium)
            AtlasSettingsActionRow(
                title = "添加自定义服务商",
                detail = "配置服务名称、连接地址与密钥变量",
                icon = CodexGlyph.AddCircleOutline,
                onClick = { showCustomEditor = true }
            )
            if (showCustomEditor) {
                CodexDetailSheet(
                    title = "添加自定义服务商",
                    subtitle = "仅配置连接地址和密钥变量名称",
                    onDismiss = { showCustomEditor = false }
                ) {
                    AtlasFormField(customName, { customName = it }, "服务商名称")
                    AtlasFormField(customUrl, { customUrl = it }, "服务地址（HTTP / HTTPS）",
                        placeholder = "https://example.com/v1", keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        confirmedValid = customUrl.isNotBlank() && ProviderAddressPolicy.valid(customUrl),
                        error = if (customUrl.isNotBlank() && !ProviderAddressPolicy.valid(customUrl))
                            "请输入有效的 HTTP 或 HTTPS 服务地址" else null)
                    AtlasFormField(customEnvKey, { customEnvKey = it }, "密钥环境变量名称（可选）",
                        helper = "密钥值由本机环境管理；这里只填写变量名称。")
                    CodexPrimaryButton(
                        onClick = { confirmCustom = true },
                        enabled = customName.isNotBlank() &&
                            ProviderAddressPolicy.valid(customUrl),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存自定义服务商") }
                }
            }
            DividerLine()
            AtlasSettingsActionRow(
                title = "查看服务商能力",
                detail = "读取实际服务能力与已声明的支持情况",
                icon = CodexGlyph.Info,
                onClick = {
                    showCapabilities = true
                    vm.refresh("providerCapabilities", "modelProvider/capabilities/read")
                }
            )
            if (showCapabilities) CodexDetailSheet(
                title = "服务商能力",
                onDismiss = { showCapabilities = false }
            ) {
                val caps = data["providerCapabilities"]
                val loading by vm.loading.collectAsState()
                val errors by vm.errors.collectAsState()
                if ("providerCapabilities" in loading) {
                    AtlasOperationProgress("正在读取服务商能力")
                } else if (errors["providerCapabilities"] != null) {
                    CodexRecoveryState("服务商能力读取失败",
                        errors.getValue("providerCapabilities"), "重新读取",
                        { vm.refresh("providerCapabilities", "modelProvider/capabilities/read") })
                } else if (caps != null) {
                    Text("联网搜索：" + if (caps.optBoolean("webSearch")) "支持" else "未声明",
                        color = Ink.muted)
                    Text("图片生成：" + if (caps.optBoolean("imageGeneration")) "支持" else "未声明",
                        color = Ink.muted)
                } else {
                    CodexRecoveryState("尚未取得服务商能力", "请重新读取实际服务能力。",
                        "重新读取", { vm.refresh("providerCapabilities", "modelProvider/capabilities/read") },
                        artworkRes = R.drawable.ic_empty_content)
                }
            }
            val errors by vm.errors.collectAsState()
            errors["providerWrite"]?.let { ErrorText(it) }
        }
        entries.forEach { item ->
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CodexIcon(CodexGlyph.Dns, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Text(PresentationPolicy.providerName(item.optString("name")),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (defaultId == item.optString("id")) StatusPill("当前默认")
                }
                if (defaultId != item.optString("id")) {
                    CodexSecondaryButton(onClick = { toSelect = item }, enabled = data["providers"]?.optBoolean("canChangeDefault") == true) {
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
                    vm.refresh("account", "account/read")
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
                    after = {
                        vm.refresh("providers", "modelProvider/list")
                        vm.refresh("models", "model/list")
                        vm.refresh("account", "account/read")
                    })
                toSelect = null
            },
            onDismiss = { toSelect = null }
        )
    }
}

@Composable
fun PluginsPage(vm: CodexViewModel, navigate: (String) -> Unit = {}) {
    val engineStatus by vm.bridge.status.collectAsState()
    val workspace by vm.activeWorkspace.collectAsState()
    val epoch by vm.extensions.epoch.collectAsState()
    var installedOnly by rememberSaveable { mutableStateOf(true) }
    val activeKey = if (installedOnly) "installedPlugins" else "plugins"
    var action by remember(epoch) { mutableStateOf<Triple<JSONObject, JSONObject, Boolean>?>(null) }
    var selectedPlugin by remember(epoch) { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    var searchDraft by rememberSaveable { mutableStateOf("") }
    var searchSubmitted by rememberSaveable { mutableStateOf("") }
    var searchPage by rememberSaveable { mutableIntStateOf(0) }
    val query = if (installedOnly) installedQuery else searchDraft
    val searchActive = !installedOnly && searchSubmitted.isNotBlank() &&
        ThreadSearchSubmission.matches(searchDraft, searchSubmitted)
    val searchPrefix = "pluginSearch:" + searchSubmitted + ":"
    val searchKey = searchPrefix + searchPage
    val requestKeys = remember(searchPrefix, searchPage) {
        setOf("plugins", "installedPlugins", "pluginMutation") + (0..searchPage).map { searchPrefix + it }
    }
    val busy by vm.loading.collectProjection { it.intersect(requestKeys) }
    val errors by vm.errors.collectEntries(*requestKeys.toTypedArray())
    val data by vm.data.collectEntries("plugins", "installedPlugins",
        *(if (searchActive) (0..searchPage).map { searchPrefix + it } else emptyList()).toTypedArray())
    val marketData = data[activeKey]
    val marketplaces = remember(marketData) { marketData?.optJSONArray("marketplaces")?.objects().orEmpty() }
    val searchReady = searchActive && data[searchPrefix + "0"] != null
    val found = remember(data, searchReady, searchPrefix, searchPage) { if (searchReady) (0..searchPage)
        .flatMap { PluginSearchContract.entries(data[searchPrefix + it]) }
        .distinctBy { it.marketplace.optString("name") + ":" + it.plugin.optString("id").ifBlank { it.plugin.optString("name") } }
        else emptyList() }
    val searchCursor = if (searchReady) ExtensionCatalogPolicy.cursor(data[searchKey]) else ""
    val connected = engineStatus == EngineStatus.Connected
    val mutating = "pluginMutation" in busy
    val canOperate = connected && !mutating
    val installedData = data["installedPlugins"]
    val installedEntries = remember(installedData) { ExtensionCatalogPolicy.installedIdentities(installedData) }
    val installedVerified = installedData != null && "installedPlugins" !in errors &&
        (installedData.optJSONArray("marketplaceLoadErrors")?.length() ?: 0) == 0
    val canManage = canOperate && activeKey !in busy && "installedPlugins" !in busy && installedVerified
    fun installed(market: JSONObject, item: JSONObject) =
        ExtensionCatalogPolicy.isInstalled(market, item, installedEntries, installedVerified)
    val allGroups = remember(marketplaces, installedOnly) {
        marketplaces.map { market -> market to ExtensionCatalogPolicy.plugins(market, installedOnly) }
    }
    val groups = remember(allGroups, query) {
        allGroups.map { (market, items) -> market to items.filter { ExtensionCatalogPolicy.matches(it, query) } }
            .filter { it.second.isNotEmpty() }
    }
    val allCount = remember(allGroups) { allGroups.sumOf { it.second.size } }
    val visibleCount = groups.sumOf { it.second.size }
    fun refreshCatalog(force: Boolean = false) {
        if (!connected || activeKey in busy) return
        vm.extensions.refreshPluginCatalog(activeKey, workspace, force)
        if (!installedOnly && (force || installedData == null) && "installedPlugins" !in busy)
            vm.extensions.refreshPluginCatalog("installedPlugins", workspace, force)
    }
    fun submitSearch(term: String = searchDraft.trim()) {
        if (!canOperate || term.isBlank()) return
        searchSubmitted = term
        searchPage = 0
        vm.refresh("pluginSearch:" + term + ":0", "plugin/search", PluginSearchContract.params(term))
    }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("plugins") == "discover") installedOnly = false
    }
    LaunchedEffect(activeKey, workspace, epoch, engineStatus) {
        if (connected) {
            if (CatalogRequestContract.shouldLoadCatalog(activeKey, data.keys, busy)) refreshCatalog()
            else if (!installedOnly && CatalogRequestContract.shouldLoadCatalog("installedPlugins", data.keys, busy))
                vm.extensions.refreshPluginCatalog("installedPlugins", workspace)
        }
    }
    LaunchedEffect(epoch, engineStatus, searchActive, searchPrefix) {
        val firstKey = searchPrefix + "0"
        if (connected && searchActive && data[firstKey] == null && firstKey !in busy && firstKey !in errors)
            submitSearch(searchSubmitted)
    }
    ScreenColumn {
        AtlasCompactChoice(if (installedOnly) "installed" else "catalog",
            listOf("installed" to "已安装", "catalog" to "发现插件"),
            onSelect = { installedOnly = it == "installed" }, tagPrefix = "plugins-tab-")
        ExtensionCatalogHeader(
            title = if (installedOnly) "已安装插件" else "插件源",
            summary = if (data[activeKey] == null) null else
                if (query.isBlank()) "$allCount 个插件" else "$visibleCount / $allCount 个插件",
            explanation = "点击插件查看能力与来源。安装和卸载由 Codex 核心执行，操作成功后更新目录。",
            refreshing = activeKey in busy, enabled = connected && !mutating,
            onRefresh = { refreshCatalog(true) })
        if (!installedOnly || data[activeKey] != null && allCount > 0) {
            if (!installedOnly) Text("搜索插件", modifier = Modifier.testTag("plugins-search-heading"),
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            AtlasSearchField(value = query,
                onValueChange = { if (installedOnly) installedQuery = it else searchDraft = it },
                placeholder = if (installedOnly) "搜索已安装插件" else "插件名称或关键字",
                enabled = if (installedOnly) true else !mutating,
                busy = searchActive && searchKey in busy,
                onClear = {
                    if (installedOnly) installedQuery = "" else {
                        searchDraft = ""; searchSubmitted = ""; searchPage = 0
                    }
                },
                onSubmit = if (installedOnly || !connected) null else ({ submitSearch() }),
                submitTestTag = "plugins-search-submit")
        }
        ProtocolFeedback(vm, listOf("pluginMutation"))
        if (!installedOnly && connected && !installedVerified && "installedPlugins" !in busy)
            CodexNotice("安装状态待核对，确认后可管理插件。", NoticeTone.Warning,
                actionLabel = "重试", onAction = { vm.extensions.refreshPluginCatalog("installedPlugins", workspace) })
        val catalogErrors = data[activeKey]?.optJSONArray("marketplaceLoadErrors")?.length() ?: 0
        if (catalogErrors > 0) CodexNotice("部分插件来源未能读取，已显示成功返回的内容。",
            NoticeTone.Warning, actionLabel = if (connected && activeKey !in busy) "重试" else null,
            onAction = { refreshCatalog(true) })
        if (!searchActive || found.isEmpty()) AtlasCatalogStatus(vm, activeKey, "插件",
            onRefresh = { refreshCatalog(true) }, onDiagnostics = { navigate("diagnostics") })
        if (searchActive) {
            if (searchKey in busy) {
                if (found.isEmpty() && visibleCount == 0) AtlasLoadingResults("正在搜索插件目录", placeholderRows = 2)
                else AtlasOperationProgress("正在搜索插件")
            }
            errors[searchKey]?.let {
                CodexNotice("在线搜索未完成，仍可浏览本地匹配项。", NoticeTone.Warning,
                    actionLabel = if (canOperate) "重试" else null, onAction = {
                        if (searchPage == 0) submitSearch()
                        else {
                            val cursor = ExtensionCatalogPolicy.cursor(data[searchPrefix + (searchPage - 1)])
                            if (cursor.isNotBlank()) vm.refresh(searchKey, "plugin/search",
                                PluginSearchContract.params(searchSubmitted, cursor))
                        }
                    })
            }
            if (found.isNotEmpty()) {
                AtlasListHeading("搜索结果", found.size, suffix = "个插件")
                MaterialPanel(Modifier.fillMaxWidth()) {
                    found.forEachIndexed { index, hit ->
                        if (index > 0) HorizontalDivider(color = Ink.border.copy(alpha = .5f))
                        val item = hit.plugin
                        val isInstalled = installed(hit.marketplace, item)
                        ExtensionPluginRow(item, isInstalled, canManage && searchKey !in busy,
                            source = ExtensionCatalogPolicy.marketName(hit.marketplace),
                            onDetails = { selectedPlugin = hit.marketplace to item },
                            onInstall = { action = Triple(hit.marketplace, item, true) })
                    }
                }
            }
            if (searchCursor.isNotBlank()) CodexSecondaryButton(onClick = {
                val next = searchPage + 1
                searchPage = next
                vm.refresh(searchPrefix + next, "plugin/search",
                    PluginSearchContract.params(searchSubmitted, searchCursor))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = canOperate && searchKey !in busy) { Text("加载更多结果") }
        }
        if (!searchActive || found.isEmpty()) groups.forEach { (market, items) ->
            Text(ExtensionCatalogPolicy.marketName(market).ifBlank { "插件目录" },
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            MaterialPanel(Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Ink.border.copy(alpha = .5f))
                    ExtensionPluginRow(item, installed(market, item), canManage,
                        onDetails = { selectedPlugin = market to item },
                        onInstall = { action = Triple(market, item, true) })
                }
            }
        }
        val searchSettled = !searchActive || (searchReady && searchKey !in busy && searchKey !in errors && data[searchKey] != null)
        if (data[activeKey] != null && visibleCount == 0 && found.isEmpty() &&
            activeKey !in busy && activeKey !in errors && catalogErrors == 0 && searchSettled) {
            if (query.isNotBlank()) CodexEmptyState("没有匹配的插件", "试试其他名称或关键字。",
                artworkRes = R.drawable.ic_empty_no_results, actionLabel = "清除搜索", onAction = {
                    if (installedOnly) installedQuery = "" else { searchDraft = ""; searchSubmitted = ""; searchPage = 0 }
                })
            else CodexEmptyState(if (installedOnly) "暂无已安装插件" else "暂无可用插件",
                if (installedOnly) "发现并安装所需插件后，可在这里管理。" else "当前目录未返回插件。",
                actionLabel = if (installedOnly) "发现插件" else "刷新目录",
                onAction = { if (installedOnly) installedOnly = false else refreshCatalog(true) })
        }
    }
    selectedPlugin?.let { (market, item) ->
        val installed = installed(market, item)
        PluginDetailSheet(marketplace = market, entry = item, vm = vm,
            onDismiss = { selectedPlugin = null }, installedState = installed,
            onManage = if (!canManage || (!installed && item.optString("installPolicy") == "NOT_AVAILABLE")) null else ({
                selectedPlugin = null; action = Triple(market, item, !installed)
            }), manageLabel = if (installed) "卸载插件" else "安装插件")
    }
    action?.let { (market, item, install) ->
        CodexConfirmDialog(title = if (install) "安装插件" else "卸载插件",
            message = if (install) "从此来源安装插件及其能力。" else "卸载会移除该插件及其提供的能力。",
            detail = ExtensionCatalogPolicy.pluginName(item) + "\n来源：" + ExtensionCatalogPolicy.marketName(market),
            confirmLabel = if (install) "确认安装" else "确认卸载", destructive = !install,
            onConfirm = {
                if (!canManage || installed(market, item) == install) {
                    action = null
                    return@CodexConfirmDialog
                }
                if (install) {
                    val params = JSONObject().put("pluginName", item.optString("name"))
                    val path = PresentationPolicy.meaningful(market.optString("path"))
                    if (path.isNotBlank()) params.put("marketplacePath", path)
                    else params.put("remoteMarketplaceName", market.optString("name"))
                    vm.extensions.changePlugin("plugin/install", params, workspace)
                } else vm.extensions.changePlugin("plugin/uninstall",
                    JSONObject().put("pluginId", item.optString("id")), workspace)
                action = null
            }, onDismiss = { action = null })
    }
}

@Composable
fun EngineDiagnosticsPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsState()
    val errors by vm.errors.collectAsState()
    val data by vm.data.collectEntries("diagnostics")
    var showLogs by remember { mutableStateOf(false) }
    var showRecoverySteps by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("diagnostics") == "logs") showLogs = true
    }
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val work = observeGlobalWorkSnapshot(vm)
    var showCrashes by remember { mutableStateOf(false) }
    var showCrashReader by remember { mutableStateOf(false) }
    var confirmClearCrashes by remember { mutableStateOf(false) }
    var crashRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    ScreenColumn {
        SectionIntro("内核诊断", "查看真实运行状态与官方诊断接口结果。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Memory, null, tint = Ink.copper)
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
            CodexDetailTrigger("查看启动日志", onClick = { showLogs = true })
            if (showLogs) {
                if (stderr.isNotEmpty()) FullTextReaderDialog("启动日志", stderr.joinToString("\n")) {
                    showLogs = false
                } else CodexInfoDialog("启动日志", onDismiss = { showLogs = false }) {
                    CodexEmptyState("暂无启动日志", "内核输出记录后可在这里查看。")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexSecondaryButton(
                    onClick = { vm.connectionRestarter.restart() },
                    enabled = !restarting && status != EngineStatus.Starting &&
                        ConnectionRestartPolicy.canRestart(work),
                    modifier = Modifier.testTag("diagnostics-restart")
                        .explainDisabled(
                            restarting || !ConnectionRestartPolicy.canRestart(work),
                            "有任务或待处理的审批，先完成后再重启内核")
                ) {
                    CodexIcon(CodexGlyph.Retry, null)
                    Text(if (restarting) "正在重新连接…" else "重启连接")
                }
                CodexSecondaryButton(onClick = { vm.refresh("diagnostics", "server/diagnostics") },
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
                CodexDetailTrigger("查看记录", onClick = { showCrashes = true })
            }
            Text("仅保存异常类型、时间和代码调用位置；不会上传执行内容。",
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            if (showCrashes) CodexDetailSheet(
                title = "本机崩溃记录",
                onDismiss = { showCrashes = false }
            ) {
                val stored = remember(showCrashes, crashRevision) {
                    LocalCrashReporter.read(context)
                }
                if (stored.isBlank()) {
                    Text("尚无本机崩溃记录", color = Ink.muted)
                } else {
                    AtlasSettingsActionRow("搜索和复制完整日志", "在独立阅读器中查看", CodexGlyph.Description,
                        onClick = { showCrashReader = true }, outlined = false)
                    if (showCrashReader) FullTextReaderDialog("本机崩溃记录", stored) {
                        showCrashReader = false
                    }
                    CodexTextAction(onClick = { confirmClearCrashes = true }) {
                        Text("清除本机崩溃记录", color = Ink.red)
                    }
                    if (confirmClearCrashes) CodexConfirmDialog(
                        title = "清除本机崩溃记录？", message = "已保存的诊断记录将被删除。",
                        confirmLabel = "确认清除", destructive = true,
                        onConfirm = {
                            LocalCrashReporter.clear(context)
                            crashRevision++
                            confirmClearCrashes = false
                        }, onDismiss = { confirmClearCrashes = false })
                }
            }
        }
        if (status is EngineStatus.Unavailable) {
            CodexDetailTrigger("查看排查步骤", onClick = { showRecoverySteps = true })
            if (showRecoverySteps) CodexDetailSheet(
                title = "连接排查",
                onDismiss = { showRecoverySteps = false }
            ) {
                Text("先重新连接运行服务；再核实账号登录状态和失败的功能。若仍无法使用，可查看启动日志。",
                    color = Ink.parchment)
            }
        }
    }
}

