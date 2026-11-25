package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A contextual reply is a single conversation turn, rather than a persistent editable field.
 * The enclosing business action owns the accepted value and its actual confirmation/RPC.
 * Cancellation never edits the original; secret replies use the protected input primitive.
 */
@Composable
internal fun ActionReplyRow(
    value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    label: @Composable () -> Unit, placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else 6, minLines: Int = 1,
    confirmedValid: Boolean = false
) {
    val secret = visualTransformation is PasswordVisualTransformation
    if (secret) {
        CodexInputField(value, onValueChange, modifier = modifier.fillMaxWidth(), enabled = enabled,
            label = label, placeholder = placeholder, supportingText = supportingText,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            isError = isError, confirmedValid = confirmedValid)
        return
    }
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).codexPressable(enabled = enabled, role = Role.Button) { open = true },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            label()
            Text(value.takeIf { it.isNotBlank() } ?: "尚未提供",
                style = MaterialTheme.typography.bodySmall, color = if (isError) Ink.red else Ink.muted,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            supportingText?.invoke()
        }
        Text(if (value.isBlank()) "回复" else "修改", color = Ink.muted,
            style = MaterialTheme.typography.labelMedium)
        CodexIcon(CodexGlyph.ChevronRight, null, modifier = Modifier.size(18.dp))
    }
    if (open) {
        var draft by remember { mutableStateOf(value) }
        val clipboard = LocalClipboardManager.current
        var clipboardEmpty by remember { mutableStateOf(false) }
        CodexDetailSheet("回复当前问题", onDismiss = { open = false }, startExpanded = true,
            footer = {
                CodexPrimaryButton(onClick = { onValueChange(draft); open = false },
                    modifier = Modifier.fillMaxWidth().testTag("action-reply-send"), enabled = enabled) { Text("确认回复") }
            }) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                label()
                supportingText?.invoke()
            }
            CodexInputField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().testTag("action-reply-input"),
                placeholder = placeholder ?: { Text("回复这项操作…") },
                enabled = enabled, keyboardOptions = keyboardOptions, singleLine = singleLine,
                maxLines = maxLines, minLines = minLines)
            CodexTextAction(onClick = {
                val pasted = clipboard.getText()?.text
                clipboardEmpty = pasted.isNullOrBlank()
                if (!clipboardEmpty && pasted != null) draft = pasted
            }, enabled = enabled) { Text("粘贴回复") }
            if (clipboardEmpty) Text("剪贴板没有可用文字", color = Ink.muted)
            if (draft.isNotEmpty()) CodexTextAction(onClick = { draft = "" }) { Text("清空回复") }
        }
    }
}

/** Read-only current value and a bounded stepper; no keyboard for numeric decisions. */
@Composable
internal fun ActionNumberStepper(value: String, onValueChange: (String) -> Unit, label: String,
    min: Long, max: Long, step: Long, enabled: Boolean = true, modifier: Modifier = Modifier,
    allowUnset: Boolean = false, initial: Long = min) {
    require(min <= max && step > 0 && initial in min..max)
    val number = value.toLongOrNull()?.takeIf { it in min..max }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CodexSecondaryButton(onClick = {
                val n = number ?: initial
                onValueChange(if (n - min < step) min.toString() else (n - step).toString())
            }, enabled = enabled && (number == null || number > min)) { Text("−") }
            Text(number?.toString() ?: "由系统决定", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            CodexSecondaryButton(onClick = {
                val n = number ?: initial
                onValueChange(if (max - n < step) max.toString() else (n + step).toString())
            }, enabled = enabled && (number == null || number < max)) { Text("+") }
        }
        if (allowUnset) CodexTextAction(onClick = { onValueChange("") },
            enabled = enabled && value.isNotEmpty()) { Text("恢复默认") }
    }
}

@Composable
internal fun ActionQuestionRow(value: String, onValueChange: (String) -> Unit,
    label: String, modifier: Modifier = Modifier, placeholder: String = "",
    helper: String = "", error: String? = null, keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    enabled: Boolean = true, singleLine: Boolean = true, confirmedValid: Boolean = false) {
    ActionReplyRow(value, onValueChange, modifier = modifier,
        label = { Text(label) }, placeholder = if (placeholder.isNotBlank()) {{ Text(placeholder) }} else null,
        supportingText = if (error != null || helper.isNotBlank()) {{
            Text(error ?: helper, color = if (error != null) Ink.red else Ink.muted)
        }} else null, singleLine = singleLine, enabled = enabled,
        isError = error != null, confirmedValid = confirmedValid,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}

internal object AgentActionDraftPolicy {
    /** User's existing draft remains intact. Never send or change permissions from a UI shortcut. */
    fun append(original: String, request: String): String =
        if (request.isBlank()) original else if (original.isBlank()) request
        else if (original.endsWith(request)) original else original + "\n\n" + request
}

@Composable
internal fun AgentActionRow(vm: CodexViewModel, title: String, detail: String, request: String,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val transferring by AppDataTransfer.state.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val creating by vm.firstThreadStarting.collectAsState()
    AtlasSettingsActionRow(title, detail, CodexGlyph.Edit,
        onClick = { vm.prepareAgentAction(request) }, enabled = enabled && !transferring.busy && !switching && !creating,
        modifier = modifier, outlined = false)
}

/** Browse existing plugin directories; the original publishing contract validates again on confirmation. */
@Composable
internal fun ActionDirectoryPicker(roots: List<java.io.File>, onSelected: (java.io.File) -> Unit,
    onDismiss: () -> Unit) {
    val canonicalRoots = remember(roots) { roots.map { it.canonicalFile }.distinct() }
    var directory by remember { mutableStateOf<java.io.File?>(null) }
    var query by remember(directory) { mutableStateOf("") }
    val result by produceState<Result<List<java.io.File>>?>(null, directory) {
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val base = directory ?: return@runCatching canonicalRoots
                require(canonicalRoots.any { base.toPath().startsWith(it.toPath()) })
                requireNotNull(base.listFiles()) { "无法读取此目录" }
                    .filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .map { it.canonicalFile }
                    .filter { child -> canonicalRoots.any { child.toPath().startsWith(it.toPath()) } }
                    .sortedBy { it.name.lowercase() }
            }
        }
    }
    val selected = directory
    CodexDetailSheet("选择插件目录", onDismiss = onDismiss, startExpanded = true) {
        if (selected != null) {
            Text(selected.name, style = MaterialTheme.typography.titleMedium)
            CodexTextAction(onClick = {
                directory = if (selected in canonicalRoots) null else selected.parentFile
            }) { Text("上一级目录") }
            val selectable = selected !in canonicalRoots &&
                java.io.File(selected, ".codex-plugin/plugin.json").isFile
            CodexPrimaryButton(onClick = { onSelected(selected) }, enabled = selectable,
                modifier = Modifier.fillMaxWidth()) { Text("使用当前插件目录") }
        }
        AtlasSearchField(query, { query = it }, "搜索目录", onClear = { query = "" })
        val read = result
        if (read == null) AtlasOperationProgress("正在读取目录")
        else read.fold(onSuccess = { directories ->
            val visible = directories.filter { it.name.contains(query, ignoreCase = true) }
            if (visible.isEmpty()) Text("没有匹配的目录", color = Ink.muted)
            visible.forEach { folder ->
                AtlasSettingsActionRow(folder.name, "", CodexGlyph.Folder,
                    onClick = { directory = folder }, outlined = false)
            }
        }, onFailure = { ErrorText(it.message ?: "目录读取失败") })
    }
}
