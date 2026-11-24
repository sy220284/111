package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun EngineSettingRows(owner: String, config: JSONObject?, enabled: Boolean,
    models: List<JSONObject>, requirements: JSONObject?, requirementsRead: Boolean,
    onSelect: (EngineSetting) -> Unit) {
    val entries = EngineSettings.forOwner(owner)
    if (entries.isNotEmpty()) {
        MaterialPanel(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, setting ->
                val available = EngineSettings.choices(setting, config, models, requirements, requirementsRead)
                val editable = enabled && (setting.type !in listOf("choice", "reasoning", "model") || available.isNotEmpty())
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("engine-setting-" + setting.key)
                    .codexPressable(enabled = editable, role = Role.Button) { onSelect(setting) }
                    .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(setting.title, style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            config == null -> "尚未读取"
                            else -> setting.label(EngineSettings.valueAt(config, setting.key),
                                if (setting.type == "reasoning") EngineSettings.reasoningLabels + available else setting.choices + available)
                        }, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                        if (config != null && setting.type in listOf("choice", "reasoning", "model") && available.isEmpty()) {
                            Text(if (setting.type in listOf("reasoning", "model")) "暂无模型可用选项" else "暂无策略允许的选项",
                                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (editable) CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted,
                        modifier = Modifier.size(18.dp))
                }
                if (index != entries.lastIndex) HorizontalDivider(color = Ink.border)
            }
        }
    }
}

@Composable
internal fun EngineSettingEditor(setting: EngineSetting, current: Any?,
    available: Map<String, String>, enabled: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onSave: (Any) -> Unit,
    saveError: String? = null, saveMessage: String? = null) {
    var draft by rememberSaveable(setting.key) { mutableStateOf(setting.input(current)) }
    var edited by rememberSaveable(setting.key) { mutableStateOf(false) }
    var search by rememberSaveable(setting.key) { mutableStateOf("") }
    LaunchedEffect(current) { if (!edited) draft = setting.input(current) }
    val parsed = runCatching { setting.parse(draft, available) }
    val value = parsed.getOrNull()
    val changed = edited && value != null && !EngineSettings.sameValue(current, value)
    CodexDetailSheet(title = setting.title,
        subtitle = "保存后用于后续任务",
        onDismiss = onDismiss,
        footer = {
            CodexPrimaryButton(onClick = { value?.let(onSave) }, enabled = enabled && changed,
                busy = busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("engine-setting-save")) { Text("保存设置") }
        }) {
        Text(setting.description, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        Text("当前配置：" + setting.label(current, EngineSettings.reasoningLabels + available),
            style = MaterialTheme.typography.titleSmall)
        when (setting.type) {
            "choice", "reasoning", "model", "boolean" -> {
                val choices = if (setting.type == "boolean") linkedMapOf("true" to "开启", "false" to "关闭") else available
                if (setting.type == "model") AtlasFormField(search, { search = it }, "搜索模型")
                val visible = choices.filter { (id, label) -> search.isBlank() || id.contains(search, true) || label.contains(search, true) }
                if (visible.isEmpty()) Text("没有匹配的可用选项", color = Ink.muted)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.forEach { (id, label) ->
                        AtlasExclusiveChip(selected = draft == id, onClick = { edited = true; draft = id }, enabled = !busy,
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("engine-option-" + id))
                    }
                }
            }
            "number" -> AtlasFormField(draft, { edited = true; draft = it }, "数值（" + setting.unit + "）",
                keyboardType = KeyboardType.Number, enabled = !busy,
                error = if (draft.isNotBlank() && parsed.isFailure) parsed.exceptionOrNull()?.message else null)
            else -> AtlasFormField(draft, { edited = true; draft = it }, "执行指令", singleLine = false, enabled = !busy)
        }
        saveError?.let { ErrorText(it) }
        saveMessage?.let { CodexNotice(it, NoticeTone.Success) }
        if (!enabled && !busy) Text("当前无法保存，请检查引擎连接或重新读取配置。", color = Ink.muted)
    }
}

/** Every owner uses the same configuration lifecycle and verified write path. */
@Composable
internal fun EngineSettingsSection(vm: CodexViewModel, owner: String, focusTarget: String? = null) {
    val status by vm.bridge.status.collectAsState()
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val permissionBusy by vm.securitySaveBusy.collectAsState()
    val config = data["config"]?.optJSONObject("config")
    val saving = ENGINE_SETTING_SAVE in loading
    val connected = status == EngineStatus.Connected
    val enabled = connected && config != null && "config" !in loading && !saving && !permissionBusy
    val models = if ("models" in loading || errors["models"] != null) emptyList() else records(data["models"])
    val requirements = data["securityRequirements"]?.optJSONObject("requirements")
    val requirementsRead = data["securityRequirements"] != null &&
        "securityRequirements" !in loading && errors["securityRequirements"] == null
    var selectedKey by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    fun clearFeedback() {
        if (!saving && vm.data.value[ENGINE_SETTING_SAVE]?.optString("owner") == owner) {
            vm.errors.value = vm.errors.value - ENGINE_SETTING_SAVE
            vm.data.value = vm.data.value - ENGINE_SETTING_SAVE
        }
    }
    fun reload() {
        if (!connected) { vm.connect(); return }
        if (ENGINE_SETTING_SAVE in vm.loading.value) return
        if ("config" !in vm.loading.value)
            vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
        if (owner == "models" && "models" !in vm.loading.value) vm.refresh("models", "model/list")
        if (owner == "tools" && "securityRequirements" !in vm.loading.value)
            vm.refresh("securityRequirements", "configRequirements/read", null)
    }
    LaunchedEffect(status, owner) { if (connected) reload() }
    LaunchedEffect(owner, focusTarget) {
        if (focusTarget != null && EngineSettings.byKey(focusTarget)?.owner == owner) {
            clearFeedback()
            selectedKey = focusTarget
        }
    }
    Text(when (owner) {
        "models" -> "默认模型与推理"
        "personalization" -> "工作偏好"
        "context" -> "对话历史与上下文"
        "tools" -> "搜索能力"
        "environments" -> "后台运行策略"
        "appearance" -> "任务内容显示"
        else -> "运行偏好"
    }, style = MaterialTheme.typography.titleMedium)
    if (saving) AtlasOperationProgress("正在保存并核实配置")
    else if (!connected || config == null || "config" in loading ||
        (owner == "models" && "models" in loading) ||
        (owner == "tools" && "securityRequirements" in loading)) {
        if (connected && ("config" in loading || (owner == "models" && "models" in loading) ||
                (owner == "tools" && "securityRequirements" in loading))) {
            AtlasOperationProgress("正在读取设置")
        } else {
            CodexNotice(if (connected) "尚未读取当前配置，重新读取后即可修改。" else "引擎未连接，连接后可以修改设置。",
                NoticeTone.Info, actionLabel = if (connected) "重新读取" else "重新连接", onAction = { reload() })
        }
    }
    if (connected && "config" !in loading && errors["config"] != null) {
        CodexNotice(PresentationPolicy.readableError(errors.getValue("config")), NoticeTone.Warning,
            actionLabel = "重试", onAction = { reload() })
    }
    if (connected && ((owner == "models" && errors["models"] != null) ||
            (owner == "tools" && errors["securityRequirements"] != null))) {
        CodexNotice("可用选项读取失败", NoticeTone.Warning, actionLabel = "重试", onAction = { reload() })
    }
    val receipt = data[ENGINE_SETTING_SAVE]?.takeIf {
        it.optString("owner") == owner && (selectedKey == null || it.optString("keyPath") == selectedKey)
    }
    val saveError = if (receipt != null) errors[ENGINE_SETTING_SAVE] else null
    val saveMessage = receipt?.optString("message")?.takeIf { it.isNotBlank() }
    if (selectedKey == null) {
        saveError?.let { ErrorText(it) }
        saveMessage?.let { CodexNotice(it, NoticeTone.Success) }
    }
    EngineSettingRows(owner, if (connected) config else null, enabled, models, requirements, requirementsRead) {
        clearFeedback()
        selectedKey = it.key
    }
    EngineSettings.byKey(selectedKey.orEmpty())?.takeIf { it.owner == owner }?.let { setting ->
        val available = EngineSettings.choices(setting, config, models, requirements, requirementsRead)
        EngineSettingEditor(setting, EngineSettings.valueAt(config, setting.key), available,
            enabled && (setting.type !in listOf("choice", "reasoning", "model") || available.isNotEmpty()),
            saving, onDismiss = { selectedKey = null },
            onSave = { vm.saveEngineSetting(setting.key, it, setting.title, owner) },
            saveError = saveError, saveMessage = saveMessage)
    }
}

@Composable
internal fun PersonalizationPage(vm: CodexViewModel) {
    val target = remember { SettingsDeepLink.take("personalization") }
    ScreenColumn {
        SectionIntro("个性化设置", "设置 AI 在后续任务中应遵循的工作要求。")
        EngineSettingsSection(vm, "personalization", target)
    }
}

@Composable
internal fun ContextManagementPage(vm: CodexViewModel) {
    val target = remember { SettingsDeepLink.take("context") }
    ScreenColumn {
        SectionIntro("上下文管理", "管理长对话中的历史内容、自动压缩和工具输出保留范围。")
        EngineSettingsSection(vm, "context", target)
    }
}

@Composable
internal fun SearchCapabilityPage(vm: CodexViewModel) {
    val target = remember { SettingsDeepLink.take("tools") }
    ScreenColumn {
        SectionIntro("联网搜索", "决定 AI 查询网络资料的方式，具体选项取决于当前运行策略。")
        EngineSettingsSection(vm, "tools", target)
    }
}
