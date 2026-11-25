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
    if (showCatalog) CodexDetailSheet(title = "可用模型说明", startExpanded = true, onDismiss = { showCatalog = false }) {
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
    var customWorkspace by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val domesticPresets = remember(context) { DomesticModelPresets.load(context) }
    var selectedPreset by remember { mutableStateOf<DomesticModelPreset?>(null) }
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    var customApiKey by remember { mutableStateOf("") }
    var customizeProvider by remember { mutableStateOf(false) }
    val credentials by vm.bridge.domesticCredentials.collectAsState()
    val changingCredentials by vm.connectionRestarter.busy.collectAsState()
    var revokePreset by remember { mutableStateOf<DomesticModelPreset?>(null) }
    var inspectCatalog by remember { mutableStateOf<Pair<DomesticModelPreset, String>?>(null) }
    var confirmCustom by remember { mutableStateOf(false) }
    var showCapabilities by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("providers") == "custom") { selectedPreset = null; customizeProvider = false; showCustomEditor = true }
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
            Text("国内模型预置", style = MaterialTheme.typography.titleMedium)
            Text("官方国内接口 · 需单独授权，预置模型仅供选择参考。", color = Ink.muted)
            domesticPresets.forEach { preset ->
                val configured = entries.firstOrNull { preset.ownsProvider(it) }
                AtlasSettingsActionRow(
                    title = preset.name,
                    detail = preset.plan + " · " + when {
                        preset.id in credentials.failures -> "密钥需重新授权"
                        configured == null -> "待配置"
                        preset.id in credentials.loaded -> "密钥已加载"
                        else -> "待授权"
                    },
                    icon = CodexGlyph.Dns,
                    enabled = !changingCredentials,
                    onClick = {
                        selectedPreset = preset
                        selectedProviderId = configured?.optString("id")
                        customApiKey = ""
                        customName = configured?.optString("name")?.takeIf { it.isNotBlank() } ?: preset.name
                        customUrl = configured?.optString("baseUrl") ?: if (preset.needsWorkspace) "" else preset.baseUrl
                        customWorkspace = preset.workspaceId(customUrl).orEmpty()
                        customEnvKey = preset.envKey
                        customizeProvider = false
                        showCustomEditor = true
                    }
                )
            }
            DividerLine()
            Text("服务商配置", style = MaterialTheme.typography.titleMedium)
            AtlasSettingsActionRow(
                title = "添加自定义服务商",
                detail = "配置服务名称、连接地址与密钥变量",
                icon = CodexGlyph.AddCircleOutline,
                onClick = {
                    selectedPreset = null
                    selectedProviderId = null
                    customApiKey = ""
                    customName = ""; customUrl = ""; customEnvKey = ""
                    customizeProvider = false
                    showCustomEditor = true
                }
            )
            if (showCustomEditor) {
                CodexDetailSheet(
                    title = "添加自定义服务商", startExpanded = true,
                    subtitle = if (selectedPreset == null) "配置连接地址与密钥引用" else "国内官方接口与本机加密授权",
                    onDismiss = { customApiKey = ""; showCustomEditor = false }
                ) {
                    selectedPreset?.let { preset ->
                        Text("原生 Responses · " + preset.plan, color = Ink.muted)
                        Text("候选模型：" + preset.models.joinToString("、"), color = Ink.muted)
                    }
                    val preset = selectedPreset
                    if (preset == null) {
                        ActionQuestionRow(customUrl, { address ->
                            val oldName = ProviderAddressPolicy.defaultName(customUrl)
                            customUrl = address
                            if (customName.isBlank() || customName == oldName)
                                customName = ProviderAddressPolicy.defaultName(address)
                        }, "服务地址（HTTP / HTTPS）", placeholder = "https://example.com/v1",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            error = if (customUrl.isNotBlank() && !ProviderAddressPolicy.valid(customUrl)) "连接地址无效" else null,
                            validate = { value -> if (value.isNotBlank() && !ProviderAddressPolicy.valid(value)) "连接地址无效" else null },
                            modifier = Modifier.testTag("provider-endpoint"))
                        if (customName.isNotBlank()) Text("服务商：$customName", color = Ink.muted)
                        CodexTextAction(onClick = { customizeProvider = !customizeProvider },
                            modifier = Modifier.testTag("provider-custom-options")) {
                            Text(if (customizeProvider) "收起自定义选项" else "其他选项")
                        }
                        if (customizeProvider) {
                            ActionQuestionRow(customName, { customName = it }, "服务商名称")
                            ActionQuestionRow(customEnvKey, { customEnvKey = it }, "密钥环境变量名称（可选）",
                                helper = "只提供变量名称；不要在这里输入密钥值。",
                                error = if (!ProviderAddressPolicy.validCredentialReference(customEnvKey)) "仅接受环境变量名称" else null,
                                validate = { value -> if (!ProviderAddressPolicy.validCredentialReference(value)) "仅接受环境变量名称" else null })
                        }
                    } else {
                        Text(customName.ifBlank { preset.name }, style = MaterialTheme.typography.titleMedium)
                        if (preset.needsWorkspace) {
                            ActionQuestionRow(customWorkspace, { value ->
                                customWorkspace = value
                                customUrl = preset.workspaceEndpoint(value.trim())
                            }, "选择北京业务空间", helper = "使用百炼控制台中的北京业务空间 ID 与对应密钥。")
                        }
                        Text(if (customUrl.isBlank()) "等待业务空间" else "使用官方服务地址", color = Ink.muted)
                        CodexInputField(customApiKey, { customApiKey = it.take(4096) },
                            modifier = Modifier.fillMaxWidth(), label = { Text("API Key") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                            singleLine = true,
                            enabled = !changingCredentials,
                            supportingText = { Text(credentials.failures[preset.id] ?: if (DomesticProviderCredentials.has(context, preset)) "已保存本机加密密钥；留空保留" else "密钥仅保存在本机 Android Keystore 加密记录中") })
                        if (DomesticProviderCredentials.has(context, preset)) {
                            CodexSecondaryButton(onClick = { revokePreset = preset },
                                enabled = !changingCredentials) { Text("移除本机密钥") }
                        }
                    }
                    CodexPrimaryButton(
                        onClick = { confirmCustom = true },
                        enabled = !changingCredentials && customName.isNotBlank() && (preset != null || ProviderAddressPolicy.validCredentialReference(customEnvKey)) &&
                            ProviderAddressPolicy.valid(customUrl) &&
                            (selectedPreset?.matchesEndpoint(customUrl) != false) &&
                            (selectedPreset?.needsWorkspace != true || selectedPreset?.workspaceId(customUrl) != null) &&
                            (preset == null || DomesticProviderCredentials.valid(customApiKey.trim()) ||
                                (customApiKey.isBlank() && DomesticProviderCredentials.has(context, preset))),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存自定义服务商") }
                }
            }
            if (changingCredentials) AtlasOperationProgress("正在核实配置及更新运行核心")
            credentials.failures.values.distinct().forEach { ErrorText(it) }
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
                title = "服务商能力", startExpanded = true,
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
            errors["providerDefaultWrite"]?.let { ErrorText(it) }
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
                    CodexSecondaryButton(onClick = { toSelect = item }, enabled = !changingCredentials && data["providers"]?.optBoolean("canChangeDefault") == true) {
                        Text("设为默认服务商")
                    }
                }
                domesticPresets.firstOrNull { it.ownsProvider(item) }?.let { preset ->
                    CodexSecondaryButton(onClick = { inspectCatalog = preset to item.optString("id") }, enabled = !changingCredentials) {
                        Text(if (preset.liveCatalog(item.optString("baseUrl"))) "实时模型目录 / 授权检查" else "查看候选模型")
                    }
                }
                DetailToggle(item)
            }
        }
        if (entries.isEmpty() && data["providers"] != null) {
            CodexEmptyState("暂无可用模型服务商", "当前内核没有返回已配置的服务商。")
        }
    }
    inspectCatalog?.let { (preset, id) -> DomesticCatalogSheet(vm, preset, id) { inspectCatalog = null } }
    if (confirmCustom) CodexConfirmDialog(
        title = "保存自定义模型服务商",
        message = if (selectedPreset != null) "将保存并回读配置，必要时重新启动空闲核心，验证本机密钥已加载。" else "保存自定义服务商配置。",
        detail = "名称：$customName\n服务地址：$customUrl",
        confirmLabel = "确认保存",
        onConfirm = {
            val patch = JSONObject().put("type", "customGateway")
                .put("name", customName.trim()).put("baseUrl", customUrl.trim())
            selectedProviderId?.let { patch.put("providerId", it) }
            if (customEnvKey.isNotBlank()) patch.put("envKey", customEnvKey.trim())
            val preset = selectedPreset
            val keyToSave = customApiKey.trim()
            val completed = {
                showCustomEditor = false
                customApiKey = ""; customName = ""; customUrl = ""; customEnvKey = ""
            }
            if (preset != null) {
                vm.domesticProviders.configure(preset, customName, customUrl, keyToSave, completed)
            } else vm.rpc("providerWrite", "modelProvider/config/write",
                JSONObject().put("settings", patch),
                after = {
                    vm.refresh("providers", "modelProvider/list")
                    vm.refresh("models", "model/list")
                    vm.refresh("account", "account/read")
                    completed()
                })
            confirmCustom = false
        }, onDismiss = { confirmCustom = false }
    )
    revokePreset?.let { preset ->
        CodexConfirmDialog(title = "移除本机密钥", message = "将关闭持有旧密钥的核心并移除本机密钥，历史会话保留，重新授权后才能使用该服务商。",
            confirmLabel = "确认移除", onConfirm = {
                revokePreset = null
                vm.domesticProviders.revoke(preset) {
                    customApiKey = ""
                    showCustomEditor = false
                }
            }, onDismiss = { revokePreset = null })
    }
    toSelect?.let { item ->
        CodexConfirmDialog(
            title = "切换默认模型服务商",
            message = "切换后新会话使用目标服务商默认模型；历史会话保持原服务商。目标：" +
                PresentationPolicy.providerName(item.optString("name")),
            confirmLabel = "确认切换",
            onConfirm = {
                vm.rpc("providerDefaultWrite", "modelProvider/default/set",
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
    var sharingPath by remember { mutableStateOf<String?>(null) }
    var choosingDiscovery by remember(epoch) { mutableStateOf(false) }
    var selectedPlugin by remember(epoch) { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }
    var searchDraft by rememberSaveable { mutableStateOf("") }
    var searchSubmitted by rememberSaveable { mutableStateOf("") }
    var searchPage by rememberSaveable { mutableIntStateOf(0) }
    val query = if (installedOnly) "" else searchDraft
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
        CodexSecondaryButton(onClick = { sharingPath = "" }, enabled = canOperate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plugin-sharing-entry")) {
            Text("插件共享")
        }
        ExtensionCatalogHeader(
            title = if (installedOnly) "已安装插件" else "插件源",
            summary = if (data[activeKey] == null) null else
                if (query.isBlank()) "$allCount 个插件" else "$visibleCount / $allCount 个插件",
            explanation = "点击插件查看能力与来源。安装和卸载由 Codex 核心执行，操作成功后更新目录。",
            refreshing = activeKey in busy, enabled = connected && !mutating,
            onRefresh = { refreshCatalog(true) })
        if (!installedOnly) {
            CodexSecondaryButton(onClick = { choosingDiscovery = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plugins-purpose-discovery")) {
                CodexIcon(CodexGlyph.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("按用途发现插件")
            }
            if (choosingDiscovery) CodexDetailSheet("发现插件", onDismiss = { choosingDiscovery = false }) {
                Text("选择要查询的用途，结果来自实际插件目录。", color = Ink.muted)
                listOf("代码与仓库" to "github", "设计与原型" to "figma",
                    "文档协作" to "document", "数据分析" to "data", "自动化" to "automation").forEach { (label, query) ->
                    CodexSecondaryButton(onClick = {
                        searchDraft = query
                        submitSearch(query)
                        choosingDiscovery = false
                    }, enabled = canOperate && searchKey !in busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                }
            }
            ClipboardQueryAction("使用剪贴板发现插件", searchDraft,
                onQuery = { searchDraft = it; submitSearch(it) },
                onClear = { searchDraft = ""; searchSubmitted = ""; searchPage = 0 },
                enabled = canOperate && searchKey !in busy, tag = "plugins-clipboard-query")
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
                    searchDraft = ""; searchSubmitted = ""; searchPage = 0
                })
            else CodexEmptyState(if (installedOnly) "暂无已安装插件" else "暂无可用插件",
                if (installedOnly) "发现并安装所需插件后，可在这里管理。" else "当前目录未返回插件。",
                actionLabel = if (installedOnly) "发现插件" else "刷新目录",
                onAction = { if (installedOnly) installedOnly = false else refreshCatalog(true) })
        }
    }
    sharingPath?.let { path ->
        PluginSharingSheet(vm, path, onChanged = {
            vm.extensions.refreshPluginCatalog("plugins", workspace, true)
            vm.extensions.refreshPluginCatalog("installedPlugins", workspace)
        }) { sharingPath = null }
    }
    selectedPlugin?.let { (market, item) ->
        val installed = installed(market, item)
        PluginDetailSheet(marketplace = market, entry = item, vm = vm,
            onDismiss = { selectedPlugin = null }, installedState = installed,
            onShare = if (!canOperate) null else ({ path -> selectedPlugin = null; sharingPath = path }),
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
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var showDiagnosticResult by rememberSaveable { mutableStateOf(false) }
    val stderr by vm.bridge.recentStderr.collectAsState()
    val coreIdentity by vm.bridge.runtimeUserAgent.collectAsState()
    val protection by CodexForegroundService.protectionState.collectAsState()
    val checking by vm.loading.collectProjection { "diagnostics" in it }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("diagnostics") == "logs") showLogs = true
    }
    val restarting by vm.connectionRestarter.busy.collectAsState()
    val retirement by vm.bridge.retirementBusy.collectAsState()
    val retry by vm.recoveryDelayMs.collectAsState()
    val work = observeGlobalWorkSnapshot(vm)
    var showCrashes by remember { mutableStateOf(false) }
    var showCrashReader by remember { mutableStateOf(false) }
    var confirmClearCrashes by remember { mutableStateOf(false) }
    var crashRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val connected = status == EngineStatus.Connected && !retirement && !restarting
    val canReconnect = RuntimeStatusPresentation.canReconnect(status, retirement, restarting) &&
        !work.dataTransferBusy
    val canRestart = !retirement && !restarting && status != EngineStatus.Starting &&
        ConnectionRestartPolicy.canRestart(work)
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val processArch = remember { System.getProperty("os.arch").orEmpty() }
    ScreenColumn {
        Row(Modifier.fillMaxWidth().testTag("diagnostics-overview"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape,
                color = Ink.elevated, border = BorderStroke(1.dp, Ink.copperDim)) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    CodexIcon(CodexGlyph.Memory, null, Modifier.size(32.dp), tint = Ink.copper)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本机引擎", style = MaterialTheme.typography.headlineSmall, color = Ink.parchment)
                Text(RuntimeStatusPresentation.label(status, retirement, restarting, retry),
                    color = if (connected) Ink.jade else Ink.warning,
                    style = MaterialTheme.typography.bodyMedium)
                Text("原生 Codex · stdio", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(RuntimeStatusPresentation.detail(status, retirement, restarting, retry),
            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        CodexAdaptiveActions(
            first = { modifier ->
                CodexSecondaryButton(
                    onClick = { vm.connectionRestarter.reconnect() },
                    enabled = canReconnect,
                    modifier = modifier.testTag("diagnostics-connect")
                ) {
                    CodexIcon(CodexGlyph.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("连接")
                }
            },
            second = { modifier ->
                CodexPrimaryButton(
                    onClick = { vm.connectionRestarter.restart() },
                    enabled = canRestart,
                    busy = restarting,
                    modifier = modifier.testTag("diagnostics-restart")
                        .explainDisabled(!canRestart, work.blockReason.ifBlank { "等待内核状态稳定" })
                ) {
                    CodexIcon(CodexGlyph.Retry, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重启连接")
                }
            }
        )
        if (!work.canDisruptCoreOrData && !restarting) {
            Text(work.blockReason, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("运行策略", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-runtime-policy")) {
            RuntimeInfoRow("打开应用时连接", "授权工作区后自动初始化")
            DividerLine()
            RuntimeInfoRow("异常恢复", "可恢复故障自动重连，保留历史与草稿")
            DividerLine()
            RuntimeInfoRow("后台空闲释放", "无任务时后台空闲 90 秒释放内核")
        }
        RuntimeLogPanel(stderr, onRead = { showLogs = true })
        if (showLogs) {
            if (stderr.isNotEmpty()) FullTextReaderDialog("启动日志", stderr.joinToString("\n")) {
                showLogs = false
            } else CodexInfoDialog("启动日志", onDismiss = { showLogs = false }) {
                CodexEmptyState("暂无启动日志", "内核输出记录后可在这里查看。")
            }
        }
        Text("更多", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-protection")) {
            val protectionLabel = when (protection) {
                ForegroundStartState.Ready -> "系统前台服务已就绪"
                ForegroundStartState.Starting -> "正在申请系统后台保护"
                is ForegroundStartState.Failed -> "后台保护失败，请保持应用在前台"
                ForegroundStartState.Stopped -> if (work.requiresForegroundProtection)
                    "当前工作需要后台保护，等待系统启动" else "有任务时自动启用后台保护"
            }
            RuntimeInfoRow("后台运行", protectionLabel)
            errors["foregroundProtection"]?.let { ErrorText(it) }
            DividerLine()
            AtlasSettingsActionRow("检查运行状况",
                if (checking) "正在读取内核诊断…" else "读取官方诊断结果",
                CodexGlyph.BugReport,
                onClick = { vm.refresh("diagnostics", "server/diagnostics") },
                enabled = connected && !checking, outlined = false,
                modifier = Modifier.testTag("diagnostics-refresh"))
            if (checking) AtlasOperationProgress("正在读取内核诊断")
            if (connected) {
                PresentationPolicy.visibleErrors(errors, listOf("diagnostics", "engine"))
                    .forEach { ErrorText(it) }
            } else errors["diagnostics"]?.let { ErrorText(it) }
            data["diagnostics"]?.takeIf { connected }?.let { result ->
                AtlasSettingsActionRow("查看诊断结果", "查看官方接口返回的完整数据",
                    CodexGlyph.Description, onClick = { showDiagnosticResult = true }, outlined = false)
                if (showDiagnosticResult) FullTextReaderDialog("诊断结果", result.toString(2)) {
                    showDiagnosticResult = false
                }
            }
        }
        Text("运行环境", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        MaterialPanel(Modifier.fillMaxWidth().testTag("diagnostics-environment")) {
            RuntimeInfoRow("应用版本", appVersion.ifBlank { "未读取" })
            DividerLine()
            RuntimeInfoRow("内核标识", coreIdentity?.takeIf { connected } ?: "等待内核握手回执")
            DividerLine()
            RuntimeInfoRow("进程架构", processArch.ifBlank { "未读取" })
            DividerLine()
            RuntimeInfoRow("通信方式", "stdio · JSON-RPC")
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
                title = "本机崩溃记录", startExpanded = true,
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
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("连接排查", style = MaterialTheme.typography.titleMedium)
                listOf("重新连接运行服务", "核实账号登录与失败功能", "根据上方日志定位启动异常").forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text((index + 1).toString(), color = Ink.copper, style = MaterialTheme.typography.titleMedium)
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeInfoRow(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.parchment)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}

/** A bounded preview uses the same sanitized snapshot as the full reader and copy action. */
@Composable
private fun RuntimeLogPanel(lines: List<String>, onRead: () -> Unit) {
    val copy = rememberConversationCopy()
    val context = LocalContext.current
    val logText = remember(lines) { lines.joinToString("\n") }
    Surface(Modifier.fillMaxWidth().testTag("diagnostics-log-panel"),
        shape = RoundedCornerShape(22.dp), color = RuntimeConsolePalette.background,
        contentColor = RuntimeConsolePalette.foreground) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("日志", style = MaterialTheme.typography.titleMedium)
                    Text("最近 ${lines.size} 行", style = MaterialTheme.typography.labelMedium,
                        color = RuntimeConsolePalette.secondary)
                }
                CodexIconAction(onClick = { copy(logText, CopyContentFormat.Literal) },
                    enabled = lines.isNotEmpty(), modifier = Modifier.testTag("diagnostics-log-copy")) {
                    CodexIcon(CodexGlyph.ContentCopy, "复制启动日志",
                        tint = if (lines.isNotEmpty()) RuntimeConsolePalette.foreground else RuntimeConsolePalette.secondary)
                }
                CodexIconAction(onClick = {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, logText)
                        context.startActivity(android.content.Intent.createChooser(intent, "分享启动日志"))
                    } catch (failure: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, "系统没有可用的分享应用",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = lines.isNotEmpty(), modifier = Modifier.testTag("diagnostics-log-share")) {
                    CodexIcon(CodexGlyph.Share, "分享启动日志",
                        tint = if (lines.isNotEmpty()) RuntimeConsolePalette.foreground else RuntimeConsolePalette.secondary)
                }
            }
            Text(if (lines.isEmpty()) "暂无启动日志" else lines.takeLast(6).joinToString("\n"),
                modifier = Modifier.fillMaxWidth().testTag("diagnostics-log-preview"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = RuntimeConsolePalette.foreground, maxLines = 10, overflow = TextOverflow.Ellipsis)
            CodexTextAction(onClick = onRead, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = RuntimeConsolePalette.foreground)) {
                Text("查看启动日志")
                Spacer(Modifier.width(6.dp))
                CodexIcon(CodexGlyph.ChevronRight, null)
            }
        }
    }
}

