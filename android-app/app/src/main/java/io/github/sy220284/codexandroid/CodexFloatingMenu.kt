package io.github.sy220284.codexandroid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 输入区短操作使用锚定的原生 Popup，而非占据整屏的底部面板。
 * 必须放在触发按钮的 Box 内；原生菜单根据窗口可见区域调整上下位置及滚动。
 */
@Composable
internal fun CodexFloatingMenu(
    title: String,
    onDismiss: () -> Unit,
    description: String = "",
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val viewport = rememberCodexViewport()
    val budget = CodexViewportPolicy.menu(viewport.width.value, viewport.height.value,
        if (ime > 0.dp) LocalCodexProtectedTop.current.value else 0f,
        if (wide) 320f else 264f)
    val topClearance = budget.top.dp
    val height = budget.height.dp
    // A simple menu must not steal the existing text field's IME connection.
    val focusable = ime == 0.dp
    BackHandler(enabled = !focusable, onBack = onDismiss)
    val menuWidth = budget.width.dp
    CodexPopupMenu(
        expanded = true, onDismissRequest = onDismiss, floating = true, focusable = focusable, topClearance = topClearance,
        modifier = Modifier.width(menuWidth)
            .heightIn(max = height).testTag("codex-floating-menu")
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(title, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("codex-floating-title").semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = Ink.parchment)
            if (description.isNotBlank()) Text(description,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            content()
        }
    }
}

/** 图标、名称、必要说明与勾选状态共享同一行；无逐项卡片边框。 */
@Composable
internal fun CodexFloatingMenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
    icon: CodexGlyph? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val tone = when {
        !enabled -> Ink.muted
        destructive -> Ink.red
        selected == true -> Ink.copper
        else -> Ink.parchment
    }
    val row = modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected == true) Ink.copper.copy(alpha = .09f) else androidx.compose.ui.graphics.Color.Transparent)
    Row(
        (if (selected != null) row.codexSelectable(selected, enabled, onClick)
        else row.codexPressable(enabled = enabled, role = Role.Button, onClick = onClick))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) Box(
            Modifier.size(38.dp).clip(CircleShape).background(Ink.recessed),
            contentAlignment = Alignment.Center
        ) { CodexIcon(icon, null, modifier = Modifier.size(21.dp), tint = tone) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tone)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                color = Ink.muted)
        }
        if (selected != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) CodexIcon(CodexGlyph.Check, null, Modifier.size(22.dp), tint = tone)
            }
        }
    }
}

@Composable
internal fun CodexFloatingActionMenu(title: String, actions: List<CodexSheetAction>,
    onDismiss: () -> Unit, showDescriptions: Boolean = true) {
    var handled by remember { mutableStateOf(false) }
    CodexFloatingMenu(title, onDismiss) {
        actions.forEach { action ->
            CodexFloatingMenuRow(action.label, onClick = {
                if (!handled) {
                    handled = true
                    onDismiss()
                    action.onClick()
                }
            },
                modifier = if (action.testTag == null) Modifier else Modifier.testTag(action.testTag),
                description = if (showDescriptions) action.description else "", icon = action.icon,
                enabled = action.enabled, destructive = action.destructive)
        }
    }
}
