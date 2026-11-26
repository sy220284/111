package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Render only validated display data using native Compose and the existing theme. */
@Composable
internal fun NativeRichReplyBlock(block: MessageBlock) {
    val copy = rememberConversationCopy()
    DisableSelection {
        Surface(Modifier.fillMaxWidth().testTag("message-rich-" + block.kind),
            shape = RoundedCornerShape(14.dp), color = Ink.canvas, border = BorderStroke(1.dp, Ink.border)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (block.kind == "metrics") {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth.value >= 260 * codexEffectiveFontScale()) 2 else 1
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            block.rows.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { metric ->
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(metric[0], color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                                            Text(metric[1], color = Ink.parchment, fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.headlineSmall)
                                            metric.getOrNull(2)?.let {
                                                Text(it, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if (row.size < columns) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    block.rows.forEachIndexed { index, step ->
                        if (index > 0) Text("↓", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            color = Ink.recessed) {
                            Text(step.single(), Modifier.padding(10.dp), textAlign = TextAlign.Center,
                                color = Ink.parchment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    CodexIconAction(onClick = { copy(block.source, CopyContentFormat.Markdown) },
                        modifier = Modifier.size(48.dp).testTag("message-rich-copy")) {
                        CodexIcon(CodexGlyph.ContentCopy, "复制图文数据", tint = Ink.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
