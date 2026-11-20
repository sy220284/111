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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

data class CodexChoice(
    val id: String,
    val title: String,
    val description: String = ""
)

enum class NoticeTone { Info, Success, Warning }

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
        NoticeTone.Warning -> Ink.red
    }
    val icon = when (tone) {
        NoticeTone.Info -> Icons.Outlined.Info
        NoticeTone.Success -> Icons.Outlined.CheckCircle
        NoticeTone.Warning -> Icons.Outlined.WarningAmber
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = .09f),
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
            if (actionLabel != null) TextButton(onClick = onAction) {
                Text(actionLabel, color = color)
            }
        }
    }
}

@Composable
fun CodexEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    MaterialPanel(modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = Ink.copper, modifier = Modifier.size(29.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        if (actionLabel != null) {
            Spacer(Modifier.height(7.dp))
            OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
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
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.recessed,
        contentColor = Ink.parchment,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.muted) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(5.dp))
            Text(description, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(17.dp))
            if (choices.isEmpty()) {
                CodexEmptyState("暂无可用选项", "请先连接内核或刷新目录。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(choices, key = { it.id }) { choice ->
                        val active = choice.id == selectedId
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
                                .clickable(role = Role.RadioButton) { onSelect(choice.id) },
                            color = if (active) Ink.copper.copy(alpha = .12f) else Ink.panel,
                            border = BorderStroke(1.dp, if (active) Ink.copper else Ink.border),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(choice.title, style = MaterialTheme.typography.titleMedium)
                                    if (choice.description.isNotBlank())
                                        Text(choice.description, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                                }
                                RadioButton(selected = active, onClick = null)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Read long raw execution evidence on a dedicated mobile sheet, without shrinking the run stream. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexEvidenceSheet(
    title: String,
    payload: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
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
            Text("Codex 返回的原始执行信息", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            Surface(
                color = Ink.panel,
                border = BorderStroke(1.dp, Ink.border.copy(alpha = .7f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                SelectionContainer {
                    Text(
                        payload,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 440.dp)
                            .verticalScroll(rememberScrollState()).padding(14.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.parchment
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("关闭")
                }
                Button(
                    onClick = { clipboard.setText(AnnotatedString(payload)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("复制原始记录")
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
    destructive: Boolean = false
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
                Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
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
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = Ink.red, contentColor = Ink.canvas)
                else ButtonDefaults.buttonColors(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
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
        border = BorderStroke(1.dp, Ink.border)
    ) {
        Row(Modifier.padding(15.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Ink.copper.copy(alpha = .12f), shape = RoundedCornerShape(12.dp)) {
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
