package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One motion language for a working console. All durations respect the in-app preference. */
object CodexMotion {
    const val press = 100
    const val release = 140
    const val feedback = release
    const val reveal = 240
    const val selection = 180
    const val menu = 200
    const val imageZoom = 220
    const val success = 450
    const val navigation = 280
    const val sheet = 320
    fun duration(normal: Int, reduce: Boolean): Int = if (reduce) 0 else normal
}

/** One incoming route only: outgoing pages never remain interactive or visible to accessibility. */
@Composable
internal fun CodexRouteEntrance(route: String, reducedMotion: Boolean, content: @Composable () -> Unit) {
    val progress = remember(route) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(route, reducedMotion) {
        if (reducedMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(CodexMotion.navigation))
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val visible = if (reducedMotion) 1f else progress.value
            alpha = .94f + .06f * visible
            translationY = 6.dp.toPx() * (1f - visible)
        }) { content() }
    }
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
    val interaction = remember { MutableInteractionSource() }
    this.codexActionPress(interaction, enabled)
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
    val description: String = "",
    val group: String? = null
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
        NoticeTone.Info -> Ink.muted
        NoticeTone.Success -> Ink.jade
        NoticeTone.Warning -> Ink.warning
        NoticeTone.Error -> Ink.red
    }
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val confirmation = remember(message, tone) { Animatable(if (tone == NoticeTone.Success && !reduced) .85f else 1f) }
    LaunchedEffect(message, tone, reduced) {
        if (reduced || tone != NoticeTone.Success) confirmation.snapTo(1f)
        else confirmation.animateTo(1f, tween(CodexMotion.success))
    }
    val icon = when (tone) {
        NoticeTone.Info -> CodexGlyph.Info
        NoticeTone.Success -> CodexGlyph.TaskComplete
        NoticeTone.Warning -> CodexGlyph.WarningAmber
        NoticeTone.Error -> CodexGlyph.TaskError
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (tone == NoticeTone.Info) Ink.recessed else color.copy(alpha = .075f),
        shape = RoundedCornerShape(12.dp)
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val fontScale = codexEffectiveFontScale()
            val narrow = !CodexViewportPolicy.fitsActions(maxWidth.value, fontScale, 2, 146f)
            if (actionLabel != null && narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodexIcon(icon, null, tint = color, modifier = Modifier.size(20.dp).graphicsLayer {
                            val scale = if (reduced) 1f else confirmation.value
                            scaleX = scale; scaleY = scale
                        })
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    CodexTextAction(onClick = onAction, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                        Text(actionLabel, color = if (tone == NoticeTone.Info) Ink.parchment else color)
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexIcon(icon, null, tint = color, modifier = Modifier.size(20.dp).graphicsLayer {
                            val scale = if (reduced) 1f else confirmation.value
                            scaleX = scale; scaleY = scale
                        })
                Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null) CodexTextAction(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel, color = if (tone == NoticeTone.Info) Ink.parchment else color)
                }
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
    val errors by vm.errors.collectEntries(*keys.toTypedArray())
    val loading by vm.loading.collectProjection { pending -> pending.intersect(keys.toSet()) }
    val connection by vm.bridge.status.collectAsState()
    CodexReveal(loading.isNotEmpty()) {
        AtlasOperationProgress("正在处理本页请求")
    }
    val visible = PresentationPolicy.visibleErrors(errors, keys.filterNot { it in loading })
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
    icon: CodexGlyph = CodexGlyph.Inbox,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    artworkRes: Int? = if (icon == CodexGlyph.Inbox) R.drawable.ic_empty_content else null
) {
    val compact = rememberCodexViewport().height < 640.dp || codexEffectiveFontScale() > 1.3f
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (artworkRes != null) {
            CodexStateArtwork(artworkRes, Modifier.size(width = if (compact) 88.dp else 128.dp,
                height = if (compact) 64.dp else 96.dp))
        } else {
            Surface(
                color = Ink.border.copy(alpha = .24f),
                shape = RoundedCornerShape(20.dp)
            ) {
                CodexIcon(icon, null, tint = Ink.copper,
                    modifier = Modifier.padding(17.dp).size(27.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
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
    modifier: Modifier = Modifier,
    artworkRes: Int = R.drawable.ic_empty_request_failed
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CodexEmptyState(
            title = title, description = description, icon = CodexGlyph.SyncProblem,
            actionLabel = actionLabel, onAction = onAction, artworkRes = artworkRes
        )
        if (onDiagnostics != null) {
            CodexTextAction(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
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
    CodexIconAction(
        onClick = onRefresh, enabled = !busy,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        if (busy) CodexActivityIndicator(
            Modifier.size(19.dp), strokeWidth = 2.dp, color = Ink.copper
        ) else CodexIcon(CodexGlyph.Retry, contentDescription = label)
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
    wide: Boolean = false,
    loading: Boolean = false,
    anchored: Boolean = false,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val groups = remember(choices) { CatalogNavigationPolicy.groups(choices) }
    var selectedGroup by remember(title, groups) { mutableStateOf<String?>(null) }
    var choosingGroup by remember(title, groups) { mutableStateOf(false) }
    val visible = if (choosingGroup) emptyList() else
        selectedGroup?.let { groups[it] } ?: choices
    val navigation: @Composable () -> Unit = {
        if (groups.isNotEmpty()) CodexTextAction(onClick = { choosingGroup = !choosingGroup },
            modifier = Modifier.testTag("choice-group-picker")) {
            Text(if (choosingGroup) "返回选项" else "定位分类 · " + (selectedGroup ?: "全部"))
        }
    }
    val selectGroup: (String?) -> Unit = { group -> selectedGroup = group; choosingGroup = false }
    if (anchored) {
        CodexFloatingMenu(title, onDismiss, description, wide = wide) {
            if (recoveryLabel != null && onRecovery != null) {
                CodexNotice(recoveryMessage, NoticeTone.Warning,
                    actionLabel = recoveryLabel, onAction = onRecovery)
            }
            extraContent?.invoke(this)
            navigation()
            if (loading) AtlasOperationProgress("正在读取可用选项")
            if (visible.isEmpty() && !loading && !choosingGroup) {
                Text("暂无可用选项",
                    color = Ink.muted, modifier = Modifier.padding(12.dp))
            }
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                if (choosingGroup) {
                    CodexFloatingMenuRow("全部 · ${choices.size} 项", onClick = { selectGroup(null) },
                        selected = selectedGroup == null)
                    groups.forEach { (group, entries) ->
                        CodexFloatingMenuRow("$group · ${entries.size} 项", onClick = { selectGroup(group) },
                            selected = selectedGroup == group)
                    }
                }
                visible.forEach { choice ->
                    CodexFloatingMenuRow(choice.title, onClick = { onSelect(choice.id) },
                        description = choice.description, selected = choice.id == selectedId,
                        enabled = enabled, modifier = Modifier.testTag("choice-option-" + choice.id))
                }
            }
        }
        return
    }
    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        containerColor = Ink.recessed,
        contentColor = Ink.parchment,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = atlasSheetHeight())
            .navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                CodexIconAction(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.Close, contentDescription = "关闭选择面板")
                }
            }
            // Keep the existing title and large-directory navigator accessible.
            navigation()
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(selectedGroup, choosingGroup) { listState.scrollToItem(0) }
            LazyColumn(state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .selectableGroup().testTag("choice-scroll-body"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "choice-context", contentType = "context") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (description.isNotBlank()) Text(description, color = Ink.muted,
                            style = MaterialTheme.typography.bodyMedium)
                        if (recoveryLabel != null && onRecovery != null) {
                            CodexNotice(recoveryMessage, NoticeTone.Warning,
                                actionLabel = recoveryLabel, onAction = onRecovery)
                        }
                        extraContent?.invoke(this)
                        if (loading) AtlasOperationProgress("正在读取可用选项")
                        if (choices.isEmpty() && !loading)
                            CodexEmptyState("暂无可用选项", "请先连接内核或刷新目录。")
                    }
                }
                if (choosingGroup) {
                    item(key = "choice-group-all") {
                        AtlasChoiceRow("全部 · ${choices.size} 项", "", selectedGroup == null, onClick = { selectGroup(null) })
                    }
                    items(groups.entries.toList(), key = { "choice-group-" + it.key }) { (group, entries) ->
                        AtlasChoiceRow("$group · ${entries.size} 项", "", selectedGroup == group,
                            onClick = { selectGroup(group) })
                    }
                }
                items(visible, key = { "choice-item-" + it.id }, contentType = { "choice" }) { choice ->
                    AtlasChoiceRow(choice.title, choice.description, choice.id == selectedId,
                        onClick = { onSelect(choice.id) }, enabled = enabled,
                        modifier = Modifier.testTag("choice-option-" + choice.id))
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
    val copy = rememberConversationCopy()
    val sections = remember(payload) { PresentationPolicy.evidenceSections(payload) }
    val copyText = remember(sections) {
        sections.joinToString("\n\n") { (label, value) -> "$label\n$value" }
    }
    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        fullScreen = true,
        containerColor = Ink.recessed,
        contentColor = Ink.parchment,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("查看本次操作的状态和实际输出",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = Ink.panel,
                border = BorderStroke(1.dp, Ink.border.copy(alpha = .7f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sections.forEach { (label, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), color = Ink.muted,
                                style = MaterialTheme.typography.labelMedium)
                            CodexIconAction(onClick = { copy(value, CopyContentFormat.Literal) },
                                enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                                CodexIcon(CodexGlyph.ContentCopy, "复制" + label, tint = Ink.muted,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
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
            CodexAdaptiveActions(
                first = { actionModifier ->
                    CodexSecondaryButton(onClick = onDismiss, modifier = actionModifier) { Text("关闭") }
                },
                second = { actionModifier ->
                    CodexSecondaryButton(onClick = { copy(copyText, CopyContentFormat.Literal) },
                        modifier = actionModifier) {
                        CodexIcon(CodexGlyph.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("复制执行详情")
                    }
                }
            )
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
    CodexDialogFrame(
        onDismissRequest = onDismiss,
        containerColor = Ink.elevated,
        titleContentColor = Ink.parchment,
        textContentColor = Ink.parchment,
        shape = RoundedCornerShape(24.dp),
        icon = {
            CodexIcon(
                if (destructive) CodexGlyph.TaskApproval else CodexGlyph.FactCheck,
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
            CodexPrimaryButton(
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
            CodexTextAction(onClick = onDismiss,
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
    icon: CodexGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Surface(
        modifier = modifier.codexActionPress(source, true)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, if (pressed) Ink.copper else Ink.border)
    ) {
        Row(Modifier.padding(15.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Ink.canvas, shape = RoundedCornerShape(12.dp)) {
                CodexIcon(icon, null, tint = Ink.copper, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Ink.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted)
        }
    }
}
