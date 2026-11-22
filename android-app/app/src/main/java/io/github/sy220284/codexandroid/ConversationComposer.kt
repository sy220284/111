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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onAttach: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("conversation-composer"),
        shape = RoundedCornerShape(23.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, Ink.copperDim.copy(alpha = .65f)),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 10.dp,
                top = 13.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 45.dp)
                    .testTag("composer-input")
                    .semantics { contentDescription = "输入任务指令" },
                minLines = 2,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.parchment),
                cursorBrush = SolidColor(Ink.copper),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (draft.isEmpty()) {
                            Text(
                                if (running) "补充要求或调整当前任务方向…"
                                else "描述任务目标、涉及文件与验收要求…",
                                color = Ink.muted,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        inner()
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(46.dp).testTag("composer-plus")
                ) {
                    Icon(Icons.Outlined.AddCircleOutline,
                        contentDescription = "添加附件", tint = Ink.copper,
                        modifier = Modifier.size(23.dp))
                }
                if (attachedCount > 0) {
                    Text("${attachedCount} 张图片",
                        color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        if (running) "任务执行中" else if (connected) "就绪" else "等待连接",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium
                    )
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
                        modifier = Modifier.heightIn(min = 48.dp).testTag("composer-stop"),
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
                CodexPrimaryButton(
                    onClick = onSend,
                    enabled = canSend,
                    busy = sending,
                    modifier = Modifier.widthIn(min = 104.dp).heightIn(min = 48.dp)
                        .testTag("composer-send")
                        .semantics { contentDescription = if (running) "补充任务指令" else "发送给 Codex" },
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(if (running) Icons.Outlined.EditNote else Icons.Outlined.ArrowUpward,
                        null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (running) "补充" else "发送")
                }
            }
        }
    }
}
