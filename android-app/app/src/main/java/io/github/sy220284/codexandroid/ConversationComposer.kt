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
    onReconnect: () -> Unit = {}
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    var compactMenuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("conversation-composer"),
        shape = RoundedCornerShape(28.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 10.dp,
                top = if (compactControls) 6.dp else 10.dp,
                bottom = if (compactControls) 6.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth()
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
                                else "询问 Codex 或描述工作任务…",
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
                AtlasCompactChoice(followUpMode,
                    listOf("queue" to "完成后执行", "steer" to "立即纠偏"),
                    onFollowUpModeChange, enabled = !sending,
                    modifier = Modifier.testTag("composer-follow-up-mode"),
                    tagPrefix = "composer-mode-")
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
                                onClick = { compactMenuExpanded = false; onMode() },
                                modifier = Modifier.testTag("composer-compact-mode"))
                            DropdownMenuItem(
                                text = { Text("运行诊断与状态") },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { compactMenuExpanded = false; onDiagnostics() },
                                modifier = Modifier.testTag("composer-compact-diagnostics"))
                        }
                    }
                    if (compactCanReconnect) {
                        TextButton(onClick = onReconnect,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("composer-reconnect")) { Text("重连") }
                    }
                }
                if (attachedCount > 0) {
                    Text("${attachedCount} 张图片",
                        color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                } else if (running || !connected) {
                    Text(if (running) "执行中"
                        else if (compactControls) "草稿保留" else "等待连接",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = running,
                    enter = expandHorizontally(tween(CodexMotion.duration(160, reduceMotion))) +
                        fadeIn(tween(CodexMotion.duration(160, reduceMotion))),
                    exit = shrinkHorizontally(tween(CodexMotion.duration(110, reduceMotion))) +
                        fadeOut(tween(CodexMotion.duration(110, reduceMotion)))
                ) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = canStop && !stopping,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("composer-stop")
                            .explainDisabled(stopping || !canStop,
                                if (stopping) "已发出停止请求，等待运行核心响应" else "当前任务尚无可停止的执行回合"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        if (stopping) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp,
                                color = Ink.red)
                        } else Icon(Icons.Outlined.StopCircle, null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (stopping) "停止中" else "停止")
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
                        containerColor = Ink.coral,
                        contentColor = androidx.compose.ui.graphics.Color.White,
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
