package io.github.sy220284.codexandroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Shared directory heading: status, help and refresh share a single line. */
@Composable
internal fun ExtensionCatalogHeader(title: String, summary: String?, explanation: String,
    refreshing: Boolean, enabled: Boolean, onRefresh: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) Text(summary, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        }
        CodexIconAction(onClick = { help = true }, modifier = Modifier.size(48.dp)) {
            CodexIcon(CodexGlyph.Info, "查看" + title + "说明", tint = Ink.muted)
        }
        CodexIconAction(onClick = onRefresh, enabled = enabled && !refreshing,
            modifier = Modifier.size(48.dp)) {
            if (refreshing) CodexActivityIndicator(Modifier.size(20.dp), color = Ink.muted, strokeWidth = 2.dp)
            else CodexIcon(CodexGlyph.Retry, "刷新" + title, tint = Ink.muted)
        }
    }
    if (help) CodexInfoDialog(title = title, onDismiss = { help = false }) { Text(explanation) }
}

/** Tap the row for details; only installation remains a direct list action. */
@Composable
internal fun ExtensionPluginRow(item: JSONObject, installed: Boolean, canOperate: Boolean,
    source: String = "", onDetails: () -> Unit, onInstall: () -> Unit) {
    val name = ExtensionCatalogPolicy.pluginName(item).ifBlank { "未命名插件" }
    val policy = item.optString("installPolicy")
    val available = policy != "NOT_AVAILABLE"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClick = onDetails),
            verticalAlignment = Alignment.CenterVertically) {
            CodexIcon(CodexGlyph.Extension, null, tint = Ink.copper, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val state = when {
                    installed && item.opt("enabled") == false -> "已安装 · 已停用"
                    installed -> "已安装"
                    !available -> "暂不可安装"
                    else -> source
                }
                if (state.isNotBlank()) Text(state, color = if (installed) Ink.jade else Ink.muted,
                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (installed || !available) CodexIcon(CodexGlyph.ChevronRight, "查看插件详情", tint = Ink.muted)
        }
        if (!installed && available) {
            Spacer(Modifier.width(8.dp))
            CodexSecondaryButton(onClick = onInstall, enabled = canOperate,
                modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-install-" + item.optString("id"))) {
                Text("安装", maxLines = 1)
            }
        }
    }
}
