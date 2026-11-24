package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset

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
    onPickImage: () -> Unit = {},
    compactControls: Boolean = false,
    compactCanReconnect: Boolean = false,
    compactModelLabel: String = "",
    compactModeLabel: String = "",
    onModel: () -> Unit = {},
    onMode: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onReconnect: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    controls: (@Composable () -> Unit)? = null
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    var compactMenuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("conversation-composer"),
        shape = RoundedCornerShape(23.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 10.dp,
                top = if (compactControls) 6.dp else 10.dp,
                bottom = if (compactControls) 6.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            controls?.invoke()
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
            // Running follow-ups belong in the composer: queue or steer the active turn.
            if (running) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("composer-follow-up-mode"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = followUpMode == "queue",
                        onClick = { onFollowUpModeChange("queue") },
                        label = { Text("完成后执行") },
                        enabled = !sending,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("composer-mode-queue")
                    )
                    FilterChip(
                        selected = followUpMode == "steer",
                        onClick = { onFollowUpModeChange("steer") },
                        label = { Text("立即纠偏") },
                        enabled = !sending,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("composer-mode-steer")
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                IconButton(
                    onClick = onPickImage,
                    enabled = !imageImporting &&
                        attachedCount < LocalImageAttachments.MAX_FILES,
                    modifier = Modifier.size(48.dp).testTag("composer-plus")
                        .explainDisabled(attachedCount >= LocalImageAttachments.MAX_FILES,
                            "最多可添加 ${LocalImageAttachments.MAX_FILES} 张照片")
                ) {
                    Icon(Icons.Outlined.AttachFile,
                        contentDescription = "选择照片",
                        tint = Ink.parchment, modifier = Modifier.size(25.dp))
                }
                if (compactControls) {
                    Box {
                        IconButton(onClick = { compactMenuExpanded = true },
                            modifier = Modifier.size(48.dp).testTag("composer-task-options")) {
                            Icon(Icons.Outlined.Tune, "模型、模式和诊断")
                        }
                        DropdownMenu(expanded = compactMenuExpanded,
                            onDismissRequest = { compactMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("选择模型 · " +
                                    compactModelLabel.ifBlank { "默认模型" },
                                    maxLines = 1) },
                                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                onClick = { compactMenuExpanded = false; onModel() },
                                modifier = Modifier.testTag("composer-compact-model"))
                            DropdownMenuItem(
                                text = { Text("任务模式 · " +
                                    (if (compactModeLabel == "plan") "先计划" else "执行")) },
                                leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                                enabled = !running && !sending,
                                onClick = { compactMenuExpanded = false; onMode() },
                                modifier = Modifier.testTag("composer-compact-mode"))
                            DropdownMenuItem(
                                text = { Text("运行诊断与状态") },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { compactMenuExpanded = false; onDiagnostics() },
                                modifier = Modifier.testTag("composer-compact-diagnostics"))
                            if (compactCanReconnect) {
                                DropdownMenuItem(text = { Text("重新连接（草稿已保留）") },
                                    onClick = { compactMenuExpanded = false; onReconnect() },
                                    modifier = Modifier.testTag("composer-reconnect"))
                            }
                        }
                    }
                }
                if (!compactControls && attachedCount > 0) {
                    Text("${attachedCount} 张图片",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = running,
                    enter = expandHorizontally(tween(CodexMotion.duration(160, reduceMotion))) +
                        fadeIn(tween(CodexMotion.duration(160, reduceMotion))),
                    exit = shrinkHorizontally(tween(CodexMotion.duration(110, reduceMotion))) +
                        fadeOut(tween(CodexMotion.duration(110, reduceMotion)))
                ) {
                    IconButton(
                        onClick = onStop,
                        enabled = canStop && !stopping,
                        modifier = Modifier.size(48.dp).testTag("composer-stop")
                            .explainDisabled(stopping || !canStop,
                                if (stopping) "已发出停止请求，等待运行核心响应" else "当前任务尚无可停止的执行回合")
                            .semantics { contentDescription = if (stopping) "正在停止任务" else "停止任务" }
                    ) {
                        if (stopping) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp,
                                color = Ink.red)
                        } else Icon(Icons.Outlined.StopCircle, null,
                            tint = Ink.red, modifier = Modifier.size(24.dp))
                    }
                }
                Button(
                    onClick = onSend,
                    enabled = canSend && !sending,
                    modifier = Modifier.size(50.dp)
                        .testTag("composer-send")
                        .explainDisabled(!canSend || sending, when {
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
                        CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp,
                            color = Ink.muted)
                    } else {
                        Icon(when {
                            !running -> Icons.Outlined.ArrowUpward
                            followUpMode == "queue" -> Icons.Outlined.Schedule
                            else -> Icons.Outlined.EditNote
                        }, null, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
