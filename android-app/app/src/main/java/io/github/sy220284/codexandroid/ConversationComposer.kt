package io.github.sy220284.codexandroid

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 对话底部是一块可自适应键盘的输入面板。业务层负责真正的 turn/start、
 * turn/steer、turn/interrupt；组件只呈现操作及其真实可用状态。
 */
@Composable
internal fun ConversationComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachedCount: Int,
    connected: Boolean,
    running: Boolean,
    stopping: Boolean,
    canStop: Boolean,
    canSend: Boolean,
    sending: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
    imageImporting: Boolean = false,
    onAddContent: () -> Unit = {},
    compactControls: Boolean = false,
    modelLabel: String = "",
    modeLabel: String = "default",
    effortLabel: String = "",
    optionsEnabled: Boolean = true,
    modelOptionsEnabled: Boolean = optionsEnabled,
    onModel: () -> Unit = {},
    onMode: () -> Unit = {},
    onTaskOptions: () -> Unit = {},
    sendBlockReason: String = "",
    onReference: ((String) -> Unit)? = null,
    shortcuts: List<ComposerShortcut> = emptyList(),
    statusContent: (@Composable () -> Unit)? = null,
    attachmentContent: (@Composable () -> Unit)? = null,
    permissionContent: (@Composable () -> Unit)? = null,
    hasInput: Boolean = draft.isNotBlank() || attachedCount > 0,
    onVoice: (() -> Unit)? = null,
    voiceEnabled: Boolean = true,
    addMenu: (@Composable () -> Unit)? = null,
    modelMenu: (@Composable () -> Unit)? = null,
    modeMenu: (@Composable () -> Unit)? = null,
    taskMenu: (@Composable () -> Unit)? = null,
    providerLabel: String = ""
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expandedEditor by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val outline by animateColorAsState(
        targetValue = if (inputFocused) Ink.parchment.copy(alpha = .36f) else Ink.controlBorder,
        animationSpec = tween(CodexMotion.duration(CodexMotion.selection, reduceMotion)),
        label = "composer-outline")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        statusContent?.invoke()
        AtlasComposerShortcuts(draft, shortcuts) { shortcut ->
            // End the old text-input session before clearing its command or
            // transferring focus to another window. The next tap can start a
            // fresh IME session after the shortcut surface closes.
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            if (ComposerShortcutPolicy.query(draft) != null) onDraftChange("")
            shortcut.onOpen()
        }
        ComposerShortcutPolicy.referenceQuery(draft)?.let { query ->
            if (onReference != null) AtlasSettingsActionRow("引用项目文件",
                query.ifBlank { "搜索并选择本机项目文件" }, CodexGlyph.Link,
                onClick = { onReference(query) }, outlined = false,
                modifier = Modifier.testTag("composer-reference-suggestion"))
        }
    Surface(
        // The IME and multiline field own their layout; animate explicit controls, not the whole composer.
        modifier = Modifier.fillMaxWidth().testTag("conversation-composer"),
        shape = RoundedCornerShape(22.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, outline),
        shadowElevation = 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp,
            vertical = if (compactControls) 3.dp else 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            attachmentContent?.invoke()
            Row(Modifier.fillMaxWidth().padding(start = 9.dp, end = 5.dp, top = 5.dp),
                verticalAlignment = Alignment.Top) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f)
                    .onFocusChanged { inputFocused = it.isFocused }
                    .heightIn(min = 48.dp)
                    .testTag("composer-input")
                    .semantics { contentDescription = "输入任务指令" },
                minLines = 1,
                maxLines = if (compactControls) 2 else 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.parchment),
                cursorBrush = SolidColor(Ink.copper),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (draft.isEmpty()) {
                            Text(
                                if (running) "补充当前任务要求…"
                                else "让 Codex 完成任务…",
                                color = Ink.muted,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        inner()
                    }
                }
            )
            if (draft.length > 100 || draft.count { it == '\n' } >= 2) {
                CodexIconAction(onClick = { expandedEditor = true }, modifier = Modifier.size(48.dp).testTag("composer-expand")) {
                    CodexIcon(CodexGlyph.OpenInFull, "展开编辑指令")
                }
            }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("composer-primary-actions")) {
                val showStop = stopping || running
                val guideVisible = running && hasInput && !stopping
                val separateModel = CodexViewportPolicy.composerModelOwnRow(maxWidth.value,
                    codexEffectiveFontScale(), 2 + (if (onVoice != null) 1 else 0) + (if (guideVisible) 1 else 0))
                Column(Modifier.fillMaxWidth()) {
                    if (separateModel) {
                        Box(Modifier.fillMaxWidth().testTag("composer-model-own-row")) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                ConversationControlBar(modelLabel, effortLabel, modelOptionsEnabled, onModel,
                                    providerLabel, expanded = true)
                            }
                            modelMenu?.invoke()
                        }
                    }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Box {
                    CodexIconAction(onClick = onAddContent, enabled = !imageImporting,
                        modifier = Modifier.size(48.dp).testTag("composer-plus")) {
                        CodexIcon(CodexGlyph.Add, "添加内容", tint = Ink.muted, modifier = Modifier.size(22.dp))
                    }
                    addMenu?.invoke()
                }
                if (separateModel) Spacer(Modifier.weight(1f))
                else Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth()) {
                        ConversationControlBar(modelLabel, effortLabel, modelOptionsEnabled, onModel, providerLabel)
                    }
                    modelMenu?.invoke()
                }
                if (onVoice != null) {
                    CodexIconAction(onClick = onVoice, enabled = voiceEnabled && !sending && !stopping,
                        modifier = Modifier.size(48.dp).testTag("composer-voice")) {
                        CodexIcon(CodexGlyph.Microphone, "语音输入", modifier = Modifier.size(22.dp), tint = Ink.muted)
                    }
                }
                // Stop stays at the same trailing edge while the user writes or sends a follow-up.
                // The independent queue action remains outside this surface.
                if (guideVisible) {
                    CodexIconAction(onClick = onSend, enabled = canSend && !sending,
                        modifier = Modifier.size(48.dp).testTag("composer-send")
                            .explainDisabled(!canSend || sending, sendBlockReason.ifBlank { "正在提交指令" })
                            .semantics { contentDescription = if (sending) "正在提交指令" else "引导当前任务" }) {
                        if (sending) CodexActivityIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Ink.muted)
                        else CodexIcon(CodexGlyph.ArrowUpward, null, modifier = Modifier.size(22.dp), tint = Ink.parchment)
                    }
                }
                val waiting = if (showStop) stopping else sending
                val primaryEnabled = !waiting && if (showStop) connected && running && canStop else canSend
                val description = when {
                    stopping -> "正在停止任务"
                    showStop -> "停止任务"
                    sending -> "正在发送指令"
                    running && hasInput -> "引导当前任务"
                    else -> "发送给 Codex"
                }
                Box(Modifier.size(48.dp).testTag("composer-primary-action")) {
                    CodexPrimaryButton(
                        onClick = if (showStop) onStop else onSend,
                        enabled = primaryEnabled,
                        modifier = Modifier.fillMaxSize()
                            .testTag(if (showStop) "composer-stop" else "composer-send")
                            .explainDisabled(!primaryEnabled, when {
                                stopping -> "已发出停止请求，等待运行核心响应"
                                !showStop && sending -> "正在发送指令，请勿重复提交"
                                !connected -> "运行核心尚未连接"
                                showStop && !canStop -> "当前任务尚无可停止的执行回合"
                                sendBlockReason.isNotBlank() -> sendBlockReason
                                else -> "输入指令后即可发送"
                            })
                            .semantics { contentDescription = description },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ink.parchment,
                            contentColor = Ink.canvas,
                            disabledContainerColor = Ink.border,
                            disabledContentColor = Ink.muted
                        )
                    ) {
                        if (waiting) {
                            CodexActivityIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Ink.muted)
                        } else {
                            Crossfade(targetState = showStop,
                                animationSpec = tween(CodexMotion.duration(CodexMotion.selection, reduceMotion)),
                                label = "composer-primary-glyph") { stop ->
                                CodexIcon(if (stop) CodexGlyph.Stop else CodexGlyph.ArrowUpward,
                                    null, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
                }
            }
        }
    }
        // Secondary context stays outside the native-style input card.
        Row(Modifier.fillMaxWidth().testTag("composer-context-footer"),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { permissionContent?.invoke() }
            Box {
                ConversationModeControl(modeLabel, optionsEnabled, onMode)
                modeMenu?.invoke()
            }
            Box {
                CodexIconAction(onClick = onTaskOptions, modifier = Modifier.size(48.dp).testTag("composer-task-options")) {
                    CodexIcon(CodexGlyph.MoreHoriz, "任务配置", tint = Ink.muted)
                }
                taskMenu?.invoke()
            }
        }
    }
    if (expandedEditor) CodexDetailSheet(title = "编辑任务指令", onDismiss = { expandedEditor = false }, startExpanded = true,
        footer = { CodexPrimaryButton(onClick = { expandedEditor = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") } }) {
        CodexInputField(draft, onDraftChange, modifier = Modifier.fillMaxWidth().testTag("composer-expanded-input"),
            minLines = 5, maxLines = 18, label = { Text("任务指令") })
    }
}
