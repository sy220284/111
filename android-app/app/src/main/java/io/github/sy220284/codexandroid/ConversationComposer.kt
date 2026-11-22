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
    onAttach: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit,
    menuExpanded: Boolean = false,
    hasThread: Boolean = false,
    imageImporting: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    onMenuRoute: (String) -> Unit = {},
    onPickImage: () -> Unit = {}
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("conversation-composer"),
        shape = RoundedCornerShape(28.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
        shadowElevation = 4.dp
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box {
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier.size(48.dp).testTag("composer-plus")
                    ) {
                        Icon(Icons.Outlined.Add,
                            contentDescription = "添加附件", tint = Ink.parchment,
                            modifier = Modifier.size(27.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = onMenuDismiss,
                        modifier = Modifier.width(268.dp).heightIn(max = 460.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .testTag("composer-attachment-menu"),
                        offset = DpOffset(0.dp, (-8).dp),
                        containerColor = Ink.canvas,
                        shadowElevation = 9.dp
                    ) {
                        Text("添加附件",
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink.parchment,
                            modifier = Modifier.padding(start = 19.dp,
                                top = 10.dp, bottom = 6.dp))
                        DropdownMenuItem(
                            modifier = Modifier.testTag("attachment-pick-image"),
                            enabled = !imageImporting &&
                                attachedCount < LocalImageAttachments.MAX_FILES,
                            text = { Text("照片") },
                            leadingIcon = {
                                Box(
                                    Modifier.size(42.dp)
                                        .background(Color(0xFFF1F1F1), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.AddPhotoAlternate,
                                        null, tint = Ink.parchment,
                                        modifier = Modifier.size(22.dp))
                                }
                            },
                            onClick = onPickImage
                        )
                        listOf("files", "terminal", "diff", "review",
                            "goals", "plugins", "modes").forEach { route ->
                            val info = pages.first { it.id == route }
                            val needsThread = UiFeatureCatalog.requiresThread(route)
                            DropdownMenuItem(
                                modifier = Modifier.testTag("quick-$route"),
                                text = {
                                    Text(if (route == "files") "工作区文件"
                                        else info.title)
                                },
                                leadingIcon = {
                                    Box(Modifier.size(42.dp)
                                            .background(Color(0xFFF1F1F1), CircleShape),
                                        contentAlignment = Alignment.Center) {
                                        Icon(info.icon, null, tint = Ink.parchment,
                                            modifier = Modifier.size(22.dp))
                                    }
                                },
                                trailingIcon = {
                                    if (needsThread && !hasThread)
                                        Text("先选会话",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Ink.muted)
                                },
                                onClick = {
                                    onMenuRoute(if (needsThread && !hasThread)
                                        "threads" else route)
                                }
                            )
                        }
                    }
                }
                if (attachedCount > 0) {
                    Text("${attachedCount} 张图片",
                        color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                } else if (running || !connected) {
                    Text(if (running) "执行中" else "等待连接",
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
                Button(
                    onClick = onSend,
                    enabled = canSend && !sending,
                    modifier = Modifier.size(50.dp)
                        .testTag("composer-send")
                        .semantics { contentDescription = if (running) "补充任务指令" else "发送给 Codex" },
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
                        Icon(if (running) Icons.Outlined.EditNote else Icons.Outlined.ArrowUpward,
                            null, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
