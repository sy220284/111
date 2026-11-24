package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared bottom action row, independent of keyboard visibility. */
@Composable
internal fun RowScope.ConversationControlBar(
    modelName: String, mode: String, effortLabel: String,
    enabled: Boolean, onModel: () -> Unit, onMode: () -> Unit
) {
    CodexTextAction(onClick = onModel, enabled = enabled,
        modifier = Modifier.weight(1.4f).testTag("conversation-model-control"),
        contentPadding = PaddingValues(horizontal = 2.dp)) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
            Text(modelName.ifBlank { "默认模型" }, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            if (effortLabel.isNotBlank()) Text(effortLabel, color = Ink.muted,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(12.dp))
    }
    CodexTextAction(onClick = onMode, enabled = enabled,
        modifier = Modifier.weight(1f).testTag("conversation-mode-control"),
        contentPadding = PaddingValues(horizontal = 1.dp)) {
        Text(if (mode == "plan") "先计划" else "执行",
            modifier = Modifier.weight(1f, fill = false), maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(12.dp))
    }
}
