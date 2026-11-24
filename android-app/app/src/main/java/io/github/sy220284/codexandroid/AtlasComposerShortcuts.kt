package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 应用本地快捷入口，只打开已有页面；明确不向模型或内核发送斜杠命令。 */
internal data class ComposerShortcut(
    val command: String, val title: String, val detail: String, val icon: CodexGlyph,
    val enabled: Boolean = true, val onOpen: () -> Unit
)

internal object ComposerShortcutPolicy {
    fun query(draft: String): String? =
        draft.takeIf { it.startsWith("/") && it.length <= 40 && it.none(Char::isWhitespace) }
            ?.drop(1)?.takeUnless { '/' in it }
    fun matches(query: String, command: String, title: String): Boolean =
        command.startsWith(query, ignoreCase = true) || title.contains(query, ignoreCase = true)
}

@Composable
internal fun AtlasComposerShortcuts(draft: String, shortcuts: List<ComposerShortcut>,
    onSelect: (ComposerShortcut) -> Unit) {
    val query = ComposerShortcutPolicy.query(draft) ?: return
    val visible = shortcuts.filter { ComposerShortcutPolicy.matches(query, it.command, it.title) }
    if (visible.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("composer-shortcuts"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("应用快捷入口 · 选择后打开对应工具",
            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 144.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(visible, key = { it.command }) { item ->
                AtlasSettingsActionRow("/" + item.command + " · " + item.title,
                    item.detail, item.icon, onClick = { onSelect(item) },
                    enabled = item.enabled, outlined = false,
                    modifier = Modifier.testTag("composer-shortcut-" + item.command))
            }
        }
    }
}
