package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

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
    followUpMode: String,
    onFollowUpModeChange: (String) -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    imageImporting: Boolean = false,
    onAddContent: () -> Unit = {},
    compactControls: Boolean = false,
    modelLabel: String = "",
    modeLabel: String = "default",
    effortLabel: String = "",
    optionsEnabled: Boolean = true,
    onModel: () -> Unit = {},
    onMode: () -> Unit = {},
    onTaskOptions: () -> Unit = {},
    sendBlockReason: String = "",
    onReference: ((String) -> Unit)? = null,
    shortcuts: List<ComposerShortcut> = emptyList(),
    statusContent: (@Composable () -> Unit)? = null,
    attachmentContent: (@Composable () -> Unit)? = null,
    permissionContent: (@Composable () -> Unit)? = null
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    var followUpMenu by remember { mutableStateOf(false) }
    var expandedEditor by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    Surface(
        // The IME and multiline field own their layout; animate explicit controls, not the whole composer.
        modifier = Modifier.fillMaxWidth().testTag("conversation-composer"),
        shape = RoundedCornerShape(23.dp),
        color = Ink.panel,
        border = BorderStroke(if (inputFocused) 1.5.dp else 1.dp,
            if (inputFocused) Ink.copper else Ink.controlBorder),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 10.dp,
                top = if (compactControls) 6.dp else 10.dp,
                bottom = if (compactControls) 6.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            statusContent?.invoke()
            attachmentContent?.invoke()
            permissionContent?.invoke()
            AtlasComposerShortcuts(draft, shortcuts) { shortcut ->
                if (ComposerShortcutPolicy.query(draft) != null) onDraftChange("")
                shortcut.onOpen()
            }
            ComposerShortcutPolicy.referenceQuery(draft)?.let { query ->
                if (onReference != null) AtlasSettingsActionRow("引用项目文件",
                    query.ifBlank { "搜索并选择本机项目文件" }, CodexGlyph.Link,
                    onClick = { onReference(query) }, outlined = false,
                    modifier = Modifier.testTag("composer-reference-suggestion"))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f)
                    .onFocusChanged { inputFocused = it.isFocused }
                    .heightIn(min = if (compactControls) 32.dp else 40.dp)
                    .testTag("composer-input")
                    .semantics { contentDescription = "输入任务指令" },
                minLines = 1,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.parchment),
                cursorBrush = SolidColor(Ink.copper),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (draft.isEmpty()) {
                            Text(
                                if (running) "补充当前任务要求…"
                                else "给 Codex 发消息…",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CodexIconAction(onClick = onAddContent, enabled = !imageImporting,
                    modifier = Modifier.size(48.dp).testTag("composer-plus")) {
                    CodexIcon(CodexGlyph.Add, "添加内容", tint = Ink.parchment, modifier = Modifier.size(24.dp))
                }
                if (running) {
                    CodexTextAction(onClick = { followUpMenu = true }, enabled = !sending,
                        modifier = Modifier.weight(1f).testTag("composer-follow-up-mode"),
                        contentPadding = PaddingValues(horizontal = 3.dp)) {
                        Text(if (followUpMode == "queue") "完成后执行" else "立即纠偏",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f, fill = false))
                        CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(14.dp))
                    }
                } else {
                    ConversationControlBar(modelLabel, modeLabel, effortLabel, optionsEnabled, onModel, onMode)
                }
                CodexIconAction(onClick = onTaskOptions, modifier = Modifier.size(48.dp).testTag("composer-task-options")) {
                    CodexIcon(CodexGlyph.Tune, "任务配置")
                }
                AnimatedVisibility(
                    visible = running,
                    enter = expandHorizontally(tween(CodexMotion.duration(CodexMotion.selection, reduceMotion))) +
                        fadeIn(tween(CodexMotion.duration(CodexMotion.selection, reduceMotion))),
                    exit = shrinkHorizontally(tween(CodexMotion.duration(CodexMotion.press, reduceMotion))) +
                        fadeOut(tween(CodexMotion.duration(CodexMotion.press, reduceMotion)))
                ) {
                    CodexIconAction(
                        onClick = onStop,
                        enabled = running && canStop && !stopping,
                        modifier = Modifier.size(48.dp).testTag("composer-stop")
                            .explainDisabled(stopping || !canStop,
                                if (stopping) "已发出停止请求，等待运行核心响应" else "当前任务尚无可停止的执行回合")
                            .semantics { contentDescription = if (stopping) "正在停止任务" else "停止任务" }
                    ) {
                        if (stopping) {
                            CodexActivityIndicator(Modifier.size(17.dp), strokeWidth = 2.dp,
                                color = Ink.red)
                        } else CodexIcon(CodexGlyph.Stop, null,
                            tint = Ink.red, modifier = Modifier.size(24.dp))
                    }
                }
                CodexPrimaryButton(
                    onClick = onSend,
                    enabled = canSend && !sending,
                    modifier = Modifier.size(50.dp)
                        .testTag("composer-send")
                        .explainDisabled(!canSend || sending, when {
                            sendBlockReason.isNotBlank() -> sendBlockReason
                            sending -> "正在发送指令，请勿重复提交"
                            !connected -> "运行核心尚未连接"
                            else -> "输入指令后即可发送"
                        })
                        .semantics { contentDescription = when {
                            !running -> "发送给 Codex"
                            followUpMode == "queue" -> "加入后续执行队列"
                            else -> "立即调整当前任务"
                        } },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink.parchment,
                        contentColor = Ink.canvas,
                        disabledContainerColor = Ink.border,
                        disabledContentColor = Ink.muted
                    )
                ) {
                    if (sending) {
                        CodexActivityIndicator(Modifier.size(21.dp), strokeWidth = 2.dp,
                            color = Ink.muted)
                    } else {
                        CodexIcon(when {
                            !running -> CodexGlyph.ArrowUpward
                            followUpMode == "queue" -> CodexGlyph.TaskRunning
                            else -> CodexGlyph.EditNote
                        }, null, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
    if (followUpMenu && running) CodexActionSheet(
        title = "发送方式", onDismiss = { followUpMenu = false },
        actions = listOf(
            CodexSheetAction("完成后执行", CodexGlyph.TaskRunning,
                onClick = { onFollowUpModeChange("queue") }, description = "加入等待队列",
                testTag = "composer-mode-queue"),
            CodexSheetAction("立即纠偏", CodexGlyph.EditNote,
                onClick = { onFollowUpModeChange("steer") }, description = "向正在执行的回合追加要求",
                testTag = "composer-mode-steer")
        )
    )
    if (expandedEditor) CodexDetailSheet(title = "编辑任务指令", onDismiss = { expandedEditor = false },
        footer = { CodexPrimaryButton(onClick = { expandedEditor = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") } }) {
        CodexInputField(draft, onDraftChange, modifier = Modifier.fillMaxWidth().testTag("composer-expanded-input"),
            minLines = 5, maxLines = 18, label = { Text("任务指令") })
    }
}
