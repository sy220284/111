package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Model identity and configuration stay readable on narrow phones. */
@Composable
internal fun RowScope.ConversationControlBar(modelName: String, effortLabel: String,
    enabled: Boolean, onModel: () -> Unit, providerLabel: String = "", expanded: Boolean = false) {
    CodexTextAction(onClick = onModel, enabled = enabled,
        modifier = Modifier.weight(1f).testTag("conversation-model-control"),
        contentPadding = PaddingValues(horizontal = 2.dp)) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(modelName.ifBlank { "默认模型" }, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, color = Ink.parchment)
            val detail = listOf(providerLabel, effortLabel).filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = Ink.muted)
        }
        Spacer(Modifier.width(2.dp))
        CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(12.dp), tint = Ink.muted)
    }
}

@Composable
internal fun ConversationModeControl(mode: String, enabled: Boolean, onMode: () -> Unit) {
    CodexTextAction(onClick = onMode, enabled = enabled,
        modifier = Modifier.testTag("conversation-mode-control"),
        contentPadding = PaddingValues(horizontal = 5.dp)) {
        CodexIcon(if (mode == "plan") CodexGlyph.Assignment else CodexGlyph.PlayArrow,
            null, Modifier.size(16.dp), tint = Ink.muted)
        Spacer(Modifier.width(4.dp))
        Text(if (mode == "plan") "先计划" else "执行",
            maxLines = 1, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(12.dp), tint = Ink.muted)
    }
}
