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
internal fun EngineSettingRows(config: JSONObject?, enabled: Boolean,
    models: List<JSONObject>, requirements: JSONObject?, requirementsRead: Boolean,
    onSelect: (EngineSetting) -> Unit) {
    EngineSettings.all.groupBy { it.group }.forEach { (group, entries) ->
        Text(group, style = MaterialTheme.typography.titleSmall, color = Ink.muted,
            modifier = Modifier.padding(top = 8.dp))
        MaterialPanel(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, setting ->
                val available = EngineSettings.choices(setting, config, models, requirements, requirementsRead)
                val editable = enabled && (setting.type !in listOf("choice", "reasoning") || available.isNotEmpty())
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
                            setting.type in listOf("choice", "reasoning") && available.isEmpty() ->
                                if (setting.type == "reasoning") "等待模型可用选项" else "等待搜索权限或策略限制"
                            else -> setting.label(EngineSettings.valueAt(config, setting.key),
                                if (setting.type == "reasoning") EngineSettings.reasoningLabels + available else setting.choices)
                        }, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
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
    onDismiss: () -> Unit, onSave: (Any) -> Unit) {
    var draft by rememberSaveable(setting.key) { mutableStateOf(setting.input(current)) }
    val parsed = runCatching { setting.parse(draft, available) }
    val value = parsed.getOrNull()
    val changed = value != null && !EngineSettings.sameValue(current, value)
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
            "choice", "reasoning", "boolean" -> {
                val choices = if (setting.type == "boolean") linkedMapOf("true" to "开启", "false" to "关闭") else available
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (id, label) ->
                        AtlasExclusiveChip(selected = draft == id, onClick = { if (!busy) draft = id },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("engine-option-" + id))
                    }
                }
            }
            "number" -> AtlasFormField(draft, { draft = it }, "数值（" + setting.unit + "）",
                keyboardType = KeyboardType.Number, enabled = !busy,
                error = if (draft.isNotBlank() && parsed.isFailure) parsed.exceptionOrNull()?.message else null)
            else -> AtlasFormField(draft, { draft = it }, "执行指令", singleLine = false, enabled = !busy)
        }
        if (!enabled && !busy) Text("当前无法保存，请检查引擎连接或重新读取配置。", color = Ink.muted)
    }
}
