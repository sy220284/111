package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 手机触控与物理键盘使用同一历史遍历策略；任何历史选择都只是填入草稿。 */
@Composable
internal fun AtlasTerminalHistoryControls(command: String, recent: List<String>,
    position: TerminalHistoryPosition, enabled: Boolean,
    onChoice: (TerminalHistoryChoice) -> Unit, onOpenHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            TerminalHistoryNavigation.previous(command, recent, position)?.let(onChoice)
        }, enabled = enabled && recent.isNotEmpty() && position.index < recent.lastIndex,
            modifier = Modifier.size(48.dp).testTag("terminal-history-previous")) {
            CodexIcon(CodexGlyph.ArrowUpward, "上一条历史命令")
        }
        IconButton(onClick = {
            TerminalHistoryNavigation.next(recent, position)?.let(onChoice)
        }, enabled = enabled && position.index >= 0,
            modifier = Modifier.size(48.dp).testTag("terminal-history-next")) {
            CodexIcon(CodexGlyph.ArrowDownward, "下一条历史命令或原草稿")
        }
        TextButton(onClick = onOpenHistory, enabled = enabled && recent.isNotEmpty(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("terminal-history-toggle")) {
            CodexIcon(CodexGlyph.History, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("使用历史命令")
        }
    }
}
