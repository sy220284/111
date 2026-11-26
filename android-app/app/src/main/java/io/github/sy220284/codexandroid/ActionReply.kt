package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.update

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
    confirmedValid: Boolean = false,
    directText: Boolean = false,
    validate: ((String) -> String?)? = null,
    replyTag: String = "action-reply",
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    val secret = visualTransformation is PasswordVisualTransformation
    if (secret || directText) {
        CodexInputField(value, onValueChange, modifier = modifier.fillMaxWidth(), enabled = enabled,
            label = label, placeholder = placeholder, supportingText = supportingText,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            isError = isError, confirmedValid = confirmedValid)
        return
    }
    var localOpen by rememberSaveable { mutableStateOf(false) }
    val open = expanded ?: localOpen
    fun setOpen(next: Boolean) {
        if (expanded == null) localOpen = next else requireNotNull(onExpandedChange)(next)
    }
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val clipboard = LocalClipboardManager.current
    var clipboardEmpty by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val draftError = validate?.invoke(draft)
    fun applyReply() {
        if (!enabled || !open || draftError != null) return
        onValueChange(draft); setOpen(false); focus.clearFocus(); keyboard?.hide()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier.fillMaxWidth().heightIn(min = 48.dp).codexPressable(enabled = enabled, role = Role.Button) {
            if (!open) draft = value
            setOpen(!open)
            if (open) { focus.clearFocus(); keyboard?.hide() }
        }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                label()
                Text(value.takeIf { it.isNotBlank() } ?: "尚未提供",
                    style = MaterialTheme.typography.bodySmall, color = if (isError) Ink.red else Ink.muted,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                supportingText?.invoke()
            }
            Text(if (open) "收起" else if (value.isBlank()) "添加" else "修改", color = Ink.muted,
                style = MaterialTheme.typography.labelMedium)
            CodexIcon(if (open) CodexGlyph.ExpandLess else CodexGlyph.ExpandMore, null,
                modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(visible = open,
            enter = expandVertically(tween(CodexMotion.duration(CodexMotion.reveal, reduced))) +
                fadeIn(tween(CodexMotion.duration(CodexMotion.selection, reduced))),
            exit = shrinkVertically(tween(CodexMotion.duration(CodexMotion.release, reduced))) +
                fadeOut(tween(CodexMotion.duration(CodexMotion.release, reduced)))) {
            Column(Modifier.fillMaxWidth().then(if (!open) Modifier.clearAndSetSemantics { } else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CodexInputField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().testTag("$replyTag-input"),
                    placeholder = placeholder ?: { Text("输入内容…") },
                    enabled = enabled && open,
                    keyboardOptions = if (singleLine) keyboardOptions.copy(imeAction = ImeAction.Done) else keyboardOptions,
                    keyboardActions = if (singleLine) KeyboardActions(onDone = { applyReply() }) else KeyboardActions.Default,
                    singleLine = singleLine,
                    maxLines = maxLines, minLines = minLines, isError = draftError != null,
                    confirmedValid = draft.isNotBlank() && draftError == null &&
                        (if (validate != null) true else confirmedValid && draft == value),
                    supportingText = if (draftError != null) {{ Text(draftError, color = Ink.red) }} else null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CodexTextAction(onClick = {
                        val pasted = clipboard.getText()?.text
                        clipboardEmpty = pasted.isNullOrBlank()
                        if (!clipboardEmpty && pasted != null) draft = pasted
                    }, enabled = enabled && open) { Text("粘贴") }
                    if (draft.isNotEmpty()) CodexTextAction(onClick = { draft = "" }, enabled = enabled && open) { Text("清空") }
                }
                if (clipboardEmpty) Text("剪贴板没有可用文字", color = Ink.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodexTextAction(onClick = { draft = value; setOpen(false); focus.clearFocus(); keyboard?.hide() },
                        modifier = Modifier.weight(1f).testTag("$replyTag-cancel"), enabled = enabled && open) { Text("取消") }
                    CodexPrimaryButton(onClick = { applyReply() },
                        modifier = Modifier.weight(1f).testTag("$replyTag-send"), enabled = enabled && open && draftError == null) { Text("应用") }
                }
            }
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
    var precise by rememberSaveable { mutableStateOf(false) }
    val increment = if (precise) 1L else step
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CodexSecondaryButton(onClick = {
                val n = number ?: initial
                onValueChange(if (n - min < increment) min.toString() else (n - increment).toString())
            }, enabled = enabled && (number == null || number > min)) { Text("−") }
            Text(number?.toString() ?: "由系统决定", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            CodexSecondaryButton(onClick = {
                val n = number ?: initial
                onValueChange(if (max - n < increment) max.toString() else (n + increment).toString())
            }, enabled = enabled && (number == null || number < max)) { Text("+") }
        }
        if (step > 1) CodexTextAction(onClick = { precise = !precise }, enabled = enabled) {
            Text(if (precise) "恢复常用调整幅度" else "精确调整（每次 1）")
        }
        if (allowUnset) CodexTextAction(onClick = { onValueChange("") },
            enabled = enabled && value.isNotEmpty()) { Text("恢复默认") }
    }
}

@Composable
internal fun ActionQuestionRow(value: String, onValueChange: (String) -> Unit,
    label: String, modifier: Modifier = Modifier, placeholder: String = "",
    helper: String = "", error: String? = null, keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    enabled: Boolean = true, singleLine: Boolean = true, confirmedValid: Boolean = false,
    directText: Boolean = false, validate: ((String) -> String?)? = null,
    replyTag: String = "action-reply", expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null) {
    ActionReplyRow(value, onValueChange, modifier = modifier,
        label = { Text(label) }, placeholder = if (placeholder.isNotBlank()) {{ Text(placeholder) }} else null,
        supportingText = if (error != null || helper.isNotBlank()) {{
            Text(error ?: helper, color = if (error != null) Ink.red else Ink.muted)
        }} else null, singleLine = singleLine, enabled = enabled,
        isError = error != null, confirmedValid = confirmedValid,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), directText = directText, validate = validate, replyTag = replyTag,
        expanded = expanded, onExpandedChange = onExpandedChange)
}

internal object AgentActionDraftPolicy {
    /** A configuration task has its own draft. Existing text, attachments and references are never combined. */
    fun canPrepare(target: ComposerDraft?, request: String): Boolean =
        request.isNotBlank() && target?.hasInput != true
}

@Composable
internal fun AgentActionRow(vm: CodexViewModel, title: String, detail: String, request: String,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val transferring by AppDataTransfer.state.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val creating by vm.firstThreadStarting.collectAsState()
    val workspace by vm.activeWorkspace.collectAsState()
    val agentOwner = ThreadComposerDrafts.agentKey(workspace)
    val drafts by vm.composerDrafts.collectEntries(agentOwner)
    val target = drafts[agentOwner]
    val canPrepare = AgentActionDraftPolicy.canPrepare(target, request)
    var preview by rememberSaveable { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    AtlasSettingsActionRow(title, detail, CodexGlyph.Edit,
        onClick = { failure = ""; preview = true }, enabled = enabled && !transferring.busy && !switching && !creating,
        modifier = modifier, outlined = false)
    if (preview) CodexConfirmDialog(
        title = "交给 Codex 处理",
        message = failure.ifBlank { if (!canPrepare) "此工作区已有一份未发送的配置任务草稿。打开查看，原普通任务草稿继续保留。"
            else "将打开独立的配置任务草稿，普通草稿、当前会话与运行任务继续保留；发送前可检查和修改内容。" },
        detail = "工作区：" + PresentationPolicy.workspaceName(workspace) + "\n\n" + request,
        confirmLabel = if (canPrepare) "打开任务草稿" else "查看配置草稿",
        confirmEnabled = enabled && !transferring.busy && !switching && !creating,
        onConfirm = {
            val opened = if (canPrepare) vm.prepareAgentAction(request) else vm.openExistingAgentDraft()
            if (opened) preview = false else failure = "当前状态发生变化，请稍后重试；草稿没有被修改。"
        }, onDismiss = { preview = false })
}

/** Browse existing plugin directories; the original publishing contract validates again on confirmation. */
@Composable
internal fun ActionDirectoryPicker(roots: List<java.io.File>, onSelected: (java.io.File) -> Unit,
    onDismiss: () -> Unit) {
    val canonicalRoots = remember(roots) { roots.map { it.canonicalFile }.distinct() }
    var directory by remember { mutableStateOf<java.io.File?>(null) }
    val selectedDirectory = directory
    val result by loadCodexUiState<Result<List<java.io.File>>?>(null, selectedDirectory, canonicalRoots) {
        codexLoadResult {
            val base = selectedDirectory
            if (base == null) canonicalRoots else {
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
        val read = result
        if (read == null) AtlasOperationProgress("正在读取目录")
        else read.fold(onSuccess = { directories ->
            if (directories.isEmpty()) Text("当前没有可用目录", color = Ink.muted)
            directories.forEach { folder ->
                AtlasSettingsActionRow(folder.name, "", CodexGlyph.Folder,
                    onClick = { directory = folder }, outlined = false)
            }
        }, onFailure = { ErrorText(it.message ?: "目录读取失败") })
    }
}

internal fun CodexViewModel.openExistingAgentDraft(): Boolean {
    val workspace = activeWorkspace.value
    if (composerDrafts.value[ThreadComposerDrafts.agentKey(workspace)]?.hasInput != true) return false
    if (!resetConversation(workspace, activeProjectId.value, agentDraft = true)) return false
    agentActionNavigation.value += 1
    return true
}

internal fun CodexViewModel.prepareAgentAction(request: String): Boolean {
    if (request.isBlank() || AppDataTransfer.state.value.busy ||
        threadSwitching.value || firstThreadStarting.value) return false
    val workspace = activeWorkspace.value
    val owner = ThreadComposerDrafts.agentKey(workspace)
    if (!AgentActionDraftPolicy.canPrepare(composerDrafts.value[owner], request)) return false
    if (!resetConversation(workspace, activeProjectId.value, agentDraft = true)) return false
    composerDrafts.update { drafts -> ThreadComposerDrafts.text(drafts, owner, request) }
    agentActionNavigation.value += 1
    return true
}
