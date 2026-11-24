package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun AnswerVersionSwitcher(choice: AnswerVersionChoice, onVersion: ((String) -> Unit)?, itemId: String) {
    Row(Modifier.testTag("answer-versions-" + itemId), verticalAlignment = Alignment.CenterVertically) {
        val previous = choice.versions.getOrNull(choice.index - 1)
        val next = choice.versions.getOrNull(choice.index + 1)
        CodexIconAction(onClick = { previous?.let { onVersion?.invoke(it.threadId) } },
            enabled = previous != null && onVersion != null, modifier = Modifier.size(48.dp).testTag("answer-previous-" + itemId)) {
            CodexIcon(CodexGlyph.ArrowBack, "上一版回答", tint = Ink.muted, modifier = Modifier.size(18.dp))
        }
        Text("${choice.index + 1} / ${choice.versions.size}", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        CodexIconAction(onClick = { next?.let { onVersion?.invoke(it.threadId) } },
            enabled = next != null && onVersion != null, modifier = Modifier.size(48.dp).testTag("answer-next-" + itemId)) {
            CodexIcon(CodexGlyph.ArrowForward, "下一版回答", tint = Ink.muted, modifier = Modifier.size(18.dp))
        }
    }
}
