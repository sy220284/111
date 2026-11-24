package io.github.sy220284.codexandroid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    searchable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val height = minOf(420.dp, config.screenHeightDp.dp * .64f,
        (config.screenHeightDp.dp - ime - 24.dp).coerceAtLeast(120.dp))
    // A simple menu must not steal the existing text field's IME connection.
    // The model search needs its own editable focus; otherwise keep the IME window.
    val focusable = searchable || ime == 0.dp
    BackHandler(enabled = !focusable, onBack = onDismiss)
    val menuWidth = minOf(if (wide) 280.dp else 248.dp,
        (config.screenWidthDp - 32).dp.coerceAtLeast(160.dp))
    CodexPopupMenu(
        expanded = true, onDismissRequest = onDismiss, floating = true, focusable = focusable,
        modifier = Modifier.width(menuWidth)
            .heightIn(max = height).testTag("codex-floating-menu")
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(title, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
    val row = modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
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
    CodexFloatingMenu(title, onDismiss) {
        actions.forEach { action ->
            CodexFloatingMenuRow(action.label, onClick = { onDismiss(); action.onClick() },
                modifier = if (action.testTag == null) Modifier else Modifier.testTag(action.testTag),
                description = if (showDescriptions) action.description else "", icon = action.icon,
                enabled = action.enabled, destructive = action.destructive)
        }
    }
}
