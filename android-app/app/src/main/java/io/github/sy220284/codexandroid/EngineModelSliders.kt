package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** The page owns the four controls; the engine continues to own persisted configuration. */
@Composable
internal fun EngineModelSliders(config: JSONObject?, enabled: Boolean, models: List<JSONObject>,
    focusTarget: String?, onSave: (EngineSetting, Any) -> Unit) {
    val entries = EngineSettings.forOwner("models")
    MaterialPanel(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, setting ->
            val available = EngineSettings.choices(setting, config, models, null, false)
            EngineModelSlider(setting, EngineSettings.valueAt(config, setting.key), available,
                config != null, enabled, focusTarget == setting.key,
                onSave = { onSave(setting, it) })
            if (index != entries.lastIndex) HorizontalDivider(color = Ink.border)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun EngineModelSlider(setting: EngineSetting, current: Any?, available: Map<String, String>,
    loaded: Boolean, enabled: Boolean, reveal: Boolean = false, onSave: (Any) -> Unit) {
    if (setting.type == "model") {
        EngineDefaultModelPicker(setting, current, available, loaded, enabled, reveal, onSave)
        return
    }
    val currentId = current?.toString()
    val base = remember(setting, available) { EngineSettings.sliderOptions(setting, available) }
    var query by rememberSaveable(setting.key) { mutableStateOf("") }
    val matching = base.filter { (id, label) ->
        id == null || id == currentId || query.isBlank() ||
            id.contains(query.trim(), true) || label.contains(query.trim(), true)
    }
    // Keep a saved value visible if the provider removed it. Never write this unavailable entry.
    val options = if (currentId != null && base.none { it.first == currentId })
        matching + (currentId to (setting.label(current, available) + " · 当前不可用")) else matching
    val actualIndex = options.indexOfFirst { it.first == currentId }.coerceAtLeast(0)
    var preview by remember(options, actualIndex, enabled) { mutableIntStateOf(actualIndex) }
    val chosen = options[preview.coerceIn(options.indices)]
    val editable = enabled && loaded && available.isNotEmpty() && options.size >= 2
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester)
        .testTag("engine-setting-" + setting.key).padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(setting.title, style = MaterialTheme.typography.titleSmall)
        Text(if (!loaded) "尚未读取" else chosen.second,
            color = if (preview != actualIndex && editable) Ink.copper else Ink.parchment,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("engine-slider-value-" + setting.key))
        if (setting.type == "model" && (available.size > 5 || query.isNotBlank())) {
            AtlasSearchField(query, { query = it }, "搜索模型", enabled = enabled,
                onClear = { query = "" }, modifier = Modifier.testTag("engine-slider-model-search"))
            if (query.isNotBlank() && available.none { (id, label) ->
                    id.contains(query.trim(), true) || label.contains(query.trim(), true) })
                Text("没有匹配的模型，当前配置仍保留", color = Ink.muted,
                    style = MaterialTheme.typography.bodySmall)
        }
        if (options.size >= 2) {
            CodexDiscreteSlider(preview, options.size, editable,
                onPreview = { preview = it },
                onFinished = {
                    val id = options[preview.coerceIn(options.indices)].first
                    if (editable && id != currentId && (id == null || id in available)) {
                        // JSON null is the native config deletion value, not an empty model name.
                        onSave(if (id == null) JSONObject.NULL else setting.parse(id, available))
                    }
                    preview = actualIndex
                },
                modifier = Modifier.testTag("engine-slider-" + setting.key).semantics {
                    contentDescription = setting.title
                    stateDescription = if (!loaded) "尚未读取" else chosen.second
                })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("由引擎决定", color = Ink.muted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f))
                Text(if (setting.type == "model") "${preview + 1} / ${options.size}" else options.last().second,
                    color = Ink.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (loaded && available.isEmpty()) Text("暂无可用选项，请检查模型目录", color = Ink.muted,
            style = MaterialTheme.typography.bodySmall)
        else if (preview != actualIndex) Text("松手后保存", color = Ink.muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** 模型名称没有天然顺序：通过可搜索目录选取，数值档位才使用滑块。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EngineDefaultModelPicker(setting: EngineSetting, current: Any?,
    available: Map<String, String>, loaded: Boolean, enabled: Boolean, reveal: Boolean,
    onSave: (Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) { if (reveal) { withFrameNanos { }; requester.bringIntoView() } }
    val id = current?.toString()
    val options = EngineSettings.sliderOptions(setting, available)
    val label = options.firstOrNull { it.first == id }?.second
        ?: (setting.label(current, available) + " · 当前不可用")
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester)
        .testTag("engine-setting-" + setting.key).padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(setting.title, style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth()) {
            CodexSecondaryButton(onClick = { open = true },
                enabled = loaded && enabled && available.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (loaded) label else "尚未读取", Modifier.weight(1f)
                    .testTag("engine-slider-value-" + setting.key))
                CodexIcon(CodexGlyph.ExpandMore, null, modifier = Modifier.size(20.dp))
            }
            if (open) CodexChoiceSheet(title = "选择默认模型", anchored = true,
                choices = options.map { (key, name) -> CodexChoice(key.orEmpty(), name) },
                selectedId = id.orEmpty(), searchable = true, enabled = enabled,
                onSelect = { key ->
                    if (key != id.orEmpty()) onSave(if (key.isEmpty()) JSONObject.NULL else setting.parse(key, available))
                    open = false
                }, onDismiss = { open = false })
        }
        if (loaded && available.isEmpty()) Text("暂无可用选项，请检查模型目录", color = Ink.muted,
            style = MaterialTheme.typography.bodySmall)
    }
}
