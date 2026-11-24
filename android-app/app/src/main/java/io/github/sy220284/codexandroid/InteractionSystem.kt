package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One motion language for a working console. All durations respect the in-app preference. */
object CodexMotion {
    const val feedback = 110
    const val reveal = 210
    const val navigation = 240
    fun duration(normal: Int, reduce: Boolean): Int = if (reduce) 0 else normal
}

/** State-driven reveal, never a perpetual decorative animation. */
@Composable
fun CodexReveal(visible: Boolean, content: @Composable () -> Unit) {
    val reducedMotion by UiPreferences.reducedMotion.collectAsState()
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(CodexMotion.duration(CodexMotion.reveal, reducedMotion))) +
            fadeIn(tween(CodexMotion.duration(CodexMotion.reveal, reducedMotion))),
        exit = shrinkVertically(tween(CodexMotion.duration(CodexMotion.feedback, reducedMotion))) +
            fadeOut(tween(CodexMotion.duration(CodexMotion.feedback, reducedMotion))),
        content = { content() }
    )
}

/**
 * 同一按压反馈用于设置行与会话列表：保留 Material ripple，
 * 并在减少动态效果时立即关闭位移动画。
 */
fun Modifier.codexPressable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed && !reduceMotion) .985f else 1f,
        animationSpec = tween(CodexMotion.duration(CodexMotion.feedback, reduceMotion)),
        label = "codex-row-press"
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            enabled = enabled,
            role = role,
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick
        )
}

data class CodexChoice(
    val id: String,
    val title: String,
    val description: String = ""
)

enum class NoticeTone { Info, Success, Warning, Error }

/** Kept in the screen hierarchy so screen readers encounter live status near the action. */
@Composable
fun CodexNotice(
    message: String,
    tone: NoticeTone = NoticeTone.Info,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    val color = when (tone) {
        NoticeTone.Info -> Ink.copper
        NoticeTone.Success -> Ink.jade
        NoticeTone.Warning -> Ink.warning
        NoticeTone.Error -> Ink.red
    }
    val icon = when (tone) {
        NoticeTone.Info -> Icons.Outlined.Info
        NoticeTone.Success -> Icons.Outlined.CheckCircle
        NoticeTone.Warning -> Icons.Outlined.WarningAmber
        NoticeTone.Error -> Icons.Outlined.ErrorOutline
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Ink.canvas,
        border = BorderStroke(1.dp, color.copy(alpha = .35f)),
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(
            Modifier.padding(start = 13.dp, top = 11.dp, bottom = 11.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) TextButton(
                onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(actionLabel, color = color)
            }
        }
    }
}

/**
 * 同一业务页面的协议反馈：以真实 ViewModel 为数据来源，将相同错误合并展示。
 * 保留不同错误的原始含义，重试回到该页面的实际请求。
 */
@Composable
fun ProtocolFeedback(
    vm: CodexViewModel,
    keys: List<String>,
    onRetry: (() -> Unit)? = null
) {
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val connection by vm.bridge.status.collectAsState()
    CodexReveal(keys.any { it in loading }) {
        AtlasOperationProgress("正在处理本页请求")
    }
    val visible = PresentationPolicy.visibleErrors(errors, keys)
    if (visible.isNotEmpty() && connection != EngineStatus.Connected) {
        CodexNotice(
            message = if (connection == EngineStatus.Starting)
                "正在连接本机运行核心，连接完成后即可加载内容。"
            else "本机运行核心尚未连接。重新连接后可继续使用当前功能。",
            tone = NoticeTone.Warning,
            actionLabel = if (connection is EngineStatus.Unavailable) "重新连接" else null,
            onAction = { vm.connect() }
        )
    } else visible.forEach { error ->
        CodexNotice(message = error, tone = NoticeTone.Error,
            actionLabel = if (onRetry == null) null else "重新加载",
            onAction = { onRetry?.invoke() })
    }
}

@Composable
fun CodexEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    artworkRes: Int? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 27.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (artworkRes != null) {
            Image(
                painter = painterResource(artworkRes),
                contentDescription = null,
                modifier = Modifier.size(width = 152.dp, height = 116.dp)
            )
        } else {
            Surface(
                color = Ink.border.copy(alpha = .24f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(icon, null, tint = Ink.copper,
                    modifier = Modifier.padding(17.dp).size(27.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge,
            color = Ink.parchment,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = Ink.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null) {
            Spacer(Modifier.height(2.dp))
            CodexPrimaryButton(onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** 能力页共享的可恢复状态：原因、重试和诊断始终指向真实动作。 */
@Composable
fun CodexRecoveryState(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CodexEmptyState(
            title = title, description = description, icon = Icons.Outlined.SyncProblem,
            actionLabel = actionLabel, onAction = onAction
        )
        if (onDiagnostics != null) {
            TextButton(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("查看诊断", color = Ink.parchment)
            }
        }
    }
}

/** Refresh actions share touch area, pending animation and disabled state. */
@Composable
fun CodexRefreshIcon(
    busy: Boolean,
    onRefresh: () -> Unit,
    label: String = "刷新"
) {
    IconButton(
        onClick = onRefresh, enabled = !busy,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        if (busy) CircularProgressIndicator(
            Modifier.size(19.dp), strokeWidth = 2.dp, color = Ink.copper
        ) else Icon(Icons.Outlined.Refresh, contentDescription = label)
    }
}

/** Single-select sheet: a comfortable thumb target for models, modes and mobile quick actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexChoiceSheet(
    title: String,
    description: String,
    choices: List<CodexChoice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    recoveryLabel: String? = null,
    onRecovery: (() -> Unit)? = null,
    recoveryMessage: String = "请先恢复运行核心，再选择可用选项。",
    searchable: Boolean = false,
    loading: Boolean = false
) {
    var query by remember { mutableStateOf("") }
    val visible = choices.filter { choice ->
        query.isBlank() || choice.title.contains(query.trim(), ignoreCase = true) ||
            choice.description.contains(query.trim(), ignoreCase = true)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.recessed,
        contentColor = Ink.parchment,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.muted) }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = atlasSheetHeight())
            .navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭选择面板")
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(description, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            if (recoveryLabel != null && onRecovery != null) {
                Spacer(Modifier.height(12.dp))
                CodexNotice(
                    recoveryMessage, NoticeTone.Warning,
                    actionLabel = recoveryLabel, onAction = onRecovery
                )
            }
            Spacer(Modifier.height(12.dp))
            if (searchable && choices.size > 1) {
                AtlasSearchField(query, { query = it }, "搜索可用模型",
                    modifier = Modifier.testTag("choice-search"), onClear = { query = "" })
                Spacer(Modifier.height(8.dp))
            }
            if (loading) AtlasOperationProgress("正在读取可用选项")
            if (choices.isEmpty()) {
                if (!loading) CodexEmptyState("暂无可用选项", "请先连接内核或刷新目录。")
            } else if (visible.isEmpty()) {
                CodexEmptyState("没有匹配的选项", "修改搜索词或查看全部。",
                    actionLabel = "清除搜索", onAction = { query = "" })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { choice ->
                        AtlasChoiceRow(choice.title, choice.description, choice.id == selectedId,
                            onClick = { onSelect(choice.id) }, enabled = enabled)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Present execution results as readable sections; protocol fields remain in the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexEvidenceSheet(
    title: String,
    payload: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val sections = remember(payload) { PresentationPolicy.evidenceSections(payload) }
    val copyText = remember(sections) {
        sections.joinToString("\n\n") { (label, value) -> "$label\n$value" }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.recessed,
        contentColor = Ink.parchment,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.muted) }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("查看本次操作的状态和实际输出",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            Surface(
                color = Ink.panel,
                border = BorderStroke(1.dp, Ink.border.copy(alpha = .7f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = atlasSheetHeight() * .62f)
                        .verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sections.forEach { (label, value) ->
                        Text(label, color = Ink.muted,
                            style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(value,
                                fontFamily = if (label == "执行输出" ||
                                    label == "错误输出" || label == "执行命令")
                                    FontFamily.Monospace else null,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.parchment)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("关闭")
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(copyText)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("复制执行详情")
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** The same explicit, readable confirmation contract for all side-effectful actions. */
@Composable
fun CodexConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确认",
    detail: String = "",
    destructive: Boolean = false,
    confirmEnabled: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.elevated,
        titleContentColor = Ink.parchment,
        textContentColor = Ink.parchment,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                if (destructive) Icons.Outlined.GppMaybe else Icons.Outlined.FactCheck,
                null, tint = if (destructive) Ink.red else Ink.copper
            )
        },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.heightIn(max = atlasSheetHeight() * .56f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotBlank()) {
                    Surface(
                        color = Ink.recessed,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Ink.border)
                    ) {
                        Text(
                            detail, modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.parchment
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) Ink.red else Ink.parchment,
                    contentColor = Ink.canvas,
                    disabledContainerColor = Ink.border, disabledContentColor = Ink.muted
                ),
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-dialog-accept")
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-dialog-cancel")) {
                Text("取消")
            }
        }
    )
}

/** Surface uses a press state rather than a perpetual decorative animation. */
@Composable
fun CodexActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduce by UiPreferences.reducedMotion.collectAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) .985f else 1f,
        animationSpec = tween(CodexMotion.duration(CodexMotion.feedback, reduce)),
        label = "action_press"
    )
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, if (pressed) Ink.copper else Ink.border)
    ) {
        Row(Modifier.padding(15.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Ink.canvas, shape = RoundedCornerShape(12.dp)) {
                Icon(icon, null, tint = Ink.copper, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Ink.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
        }
    }
}
