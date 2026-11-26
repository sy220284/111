package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 筛选摘要常驻一行；三个维度按需打开，共用侧栏原有查询状态。 */
@Composable
internal fun SidebarQueryFilters(state: SidebarUiState) {
    var open by remember { mutableStateOf(false) }
    val summary = listOf(if (state.fullText) "全文" else "标题",
        if (state.archived) "已归档" else "会话",
        if (state.projectOnly) "当前项目" else "全部项目").joinToString(" · ")
    CodexTextAction(onClick = { open = true },
        modifier = Modifier.fillMaxWidth().testTag("sidebar-query-filters")) {
        CodexIcon(CodexGlyph.Tune, "筛选会话", Modifier.size(20.dp), tint = Ink.muted)
        Spacer(Modifier.width(8.dp))
        Text(summary, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(16.dp), tint = Ink.muted)
    }
    if (open) CodexDetailSheet("筛选会话", onDismiss = { open = false },
        footer = {
            CodexPrimaryButton(onClick = { open = false },
                modifier = Modifier.fillMaxWidth().testTag("sidebar-filters-done")) { Text("查看会话") }
        }) {
        Text("检索内容", style = MaterialTheme.typography.titleSmall)
        AtlasCompactChoice(if (state.fullText) "full" else "title",
            listOf("title" to "标题搜索", "full" to "全文搜索"),
            onSelect = { state.fullText = it == "full" }, tagPrefix = "sidebar-filter-query-")
        Text("会话范围", style = MaterialTheme.typography.titleSmall)
        AtlasCompactChoice(if (state.archived) "archived" else "active",
            listOf("active" to "会话", "archived" to "已归档"),
            onSelect = { state.archived = it == "archived" }, tagPrefix = "sidebar-filter-status-")
        Text("项目范围", style = MaterialTheme.typography.titleSmall)
        AtlasCompactChoice(if (state.projectOnly) "project" else "all",
            listOf("all" to "全部项目", "project" to "当前项目"),
            onSelect = { state.projectOnly = it == "project" }, tagPrefix = "sidebar-filter-project-")
    }
}
