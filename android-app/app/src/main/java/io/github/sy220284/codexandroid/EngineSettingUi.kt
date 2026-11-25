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
    CodexDetailSheet(title = setting.title, startExpanded = true,
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
            else -> {
                if (setting.key == "developer_instructions") {
                    CodexTextAction(onClick = {
                        edited = true
                        draft = ProgressiveReplyProjection.appendInstruction(draft)
                    }, enabled = enabled && !busy && !draft.contains(ProgressiveReplyProjection.instruction),
                        modifier = Modifier.testTag("append-progressive-reply-instruction")) { Text("添加渐进式回复指令") }
                }
                AtlasFormField(draft, { edited = true; draft = it }, "执行指令", singleLine = false, enabled = !busy)
            }
        }
        saveError?.let { ErrorText(it) }
        saveMessage?.let { CodexNotice(it, NoticeTone.Success) }
        if (!enabled && !busy) Text("当前无法保存，请检查引擎连接或重新读取配置。", color = Ink.muted)
    }
}

/** Values are edited in place; only verified engine writes update the committed state. */
@Composable
private fun EngineInlineControl(setting: EngineSetting, current: Any?, loaded: Boolean,
    available: Map<String, String>, enabled: Boolean, onSave: (Any) -> Unit) {
    val currentInput = setting.input(current)
    var draft by rememberSaveable(setting.key, currentInput) { mutableStateOf(currentInput) }
    val value = runCatching { setting.parse(draft, available) }.getOrNull()
    val changed = value != null && !EngineSettings.sameValue(current, value)
    Column(Modifier.fillMaxWidth().testTag("engine-setting-" + setting.key).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(setting.title, style = MaterialTheme.typography.titleMedium)
        Text(setting.description, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        Text(if (loaded) setting.label(current, available) else "尚未读取",
            style = MaterialTheme.typography.labelMedium, color = Ink.copper)
        when (setting.type) {
            "boolean" -> {
                if (current is Boolean) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (current) "已开启" else "已关闭", modifier = Modifier.weight(1f))
                        WhiteSwitch(checked = current, onCheckedChange = { onSave(it) }, enabled = enabled)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodexSecondaryButton(onClick = { onSave(true) }, enabled = enabled) { Text("开启") }
                        CodexSecondaryButton(onClick = { onSave(false) }, enabled = enabled) { Text("关闭") }
                    }
                }
            }
            "choice" -> {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    available.forEach { (id, label) ->
                        AtlasChoiceRow(label, "", currentInput == id, onClick = { onSave(setting.parse(id, available)) },
                            enabled = enabled, modifier = Modifier.testTag("engine-option-" + id))
                    }
                }
                if (available.isEmpty()) Text("暂无策略允许的选项", color = Ink.muted)
            }
            "number" -> {
                if (setting.max == 100L && setting.unit == "%") {
                    val preview = draft.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    Text(if (draft.isBlank()) "由引擎决定" else "${preview}%",
                        color = Ink.copper, style = MaterialTheme.typography.titleMedium)
                    CodexDiscreteSlider(preview, 101, enabled,
                        onPreview = { draft = it.toString() }, onFinished = {},
                        modifier = Modifier.testTag("engine-inline-slider-" + setting.key))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0% · 关闭", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                        Text("100%", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    val number = draft.toLongOrNull()
                    val step = if (setting.unit == "秒") 1L else 100L
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodexSecondaryButton(onClick = { draft = ((number ?: setting.min) - step).coerceAtLeast(setting.min).toString() },
                            enabled = enabled && number != null && number > setting.min) { Text("−") }
                        Box(Modifier.weight(1f)) {
                            AtlasFormField(draft, { draft = it }, "数值（" + setting.unit + "）",
                                keyboardType = KeyboardType.Number, enabled = enabled,
                                error = if (draft.isNotBlank() && value == null) "请输入有效数值" else null)
                        }
                        CodexSecondaryButton(onClick = {
                            val n = number ?: setting.min
                            draft = (if (n > Long.MAX_VALUE - step) Long.MAX_VALUE else n + step)
                                .coerceAtMost(minOf(setting.max, Long.MAX_VALUE / setting.scale)).toString()
                        }, enabled = enabled && (number ?: setting.min) < minOf(setting.max, Long.MAX_VALUE / setting.scale)) { Text("+") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CodexTextAction(onClick = { draft = currentInput }, enabled = enabled && draft != currentInput) { Text("取消修改") }
                    CodexPrimaryButton(onClick = { value?.let(onSave) }, enabled = enabled && changed,
                        modifier = Modifier.testTag("engine-inline-save-" + setting.key)) { Text("保存") }
                }
            }
        }
    }
}

/** Every owner uses the same configuration lifecycle and verified write path. */
@Composable
internal fun EngineSettingsSection(vm: CodexViewModel, owner: String, focusTarget: String? = null) {
    val status by vm.bridge.status.collectAsState()
    val data by vm.data.collectEntries("config", "models", "securityRequirements", ENGINE_SETTING_SAVE)
    val loading by vm.loading.collectProjection { it.intersect(setOf("config", "models", "securityRequirements", ENGINE_SETTING_SAVE)) }
    val errors by vm.errors.collectEntries("config", "models", "securityRequirements", ENGINE_SETTING_SAVE)
    val permissionBusy by vm.securitySaveBusy.collectAsState()
    val configTransactionBusy by vm.configTransactionBusy.collectAsState()
    val otherConfigBusy by vm.loading.collectProjection { configSnapshotBusy(it, false) }
    val config = data["config"]?.optJSONObject("config")
    val saving = ENGINE_SETTING_SAVE in loading
    val connected = status == EngineStatus.Connected
    val enabled = connected && config != null && !otherConfigBusy && !saving && !permissionBusy && !configTransactionBusy && errors["config"] == null
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
            if (EngineSettings.byKey(focusTarget)?.type == "text") selectedKey = focusTarget
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
    if (owner == "models") {
        CodexTextAction(onClick = { vm.refresh("models", "model/list", ComposerProviderScope.request(forceRefresh = true)) },
            enabled = connected && "models" !in loading) { Text("刷新模型目录") }
        EngineModelSliders(if (connected) config else null, enabled, models, focusTarget) { setting, value ->
            clearFeedback()
            vm.saveEngineSetting(setting.key, value, setting.title, owner)
        }
        return
    }
    val entries = EngineSettings.forOwner(owner)
    MaterialPanel(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, setting ->
            key(setting.key) {
                val current = EngineSettings.valueAt(config, setting.key)
                val available = EngineSettings.choices(setting, config, models, requirements, requirementsRead)
                if (setting.type == "text") {
                    AtlasSettingsActionRow(setting.title, setting.label(current), CodexGlyph.Edit,
                        onClick = { clearFeedback(); selectedKey = setting.key },
                        enabled = enabled, outlined = false,
                        modifier = Modifier.testTag("engine-setting-" + setting.key))
                } else {
                    EngineInlineControl(setting, current, config != null && connected, available, enabled,
                        onSave = { value ->
                            clearFeedback()
                            vm.saveEngineSetting(setting.key, value, setting.title, owner)
                        })
                }
            }
            if (index != entries.lastIndex) HorizontalDivider(color = Ink.border)
        }
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
    var memory by remember { mutableStateOf(false) }
    if (memory) MemoryManagementSheet(vm, null) { memory = false }
    ScreenColumn {
        SectionIntro("上下文管理", "管理长对话中的历史内容、自动压缩和工具输出保留范围。")
        CodexSecondaryButton(onClick = { memory = true }, modifier = Modifier.fillMaxWidth()) { Text("全局记忆管理") }
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
