package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 搜索与分组共用唯一的功能目录，避免侧栏、设置页维护两套入口。 */
@Composable
fun SettingsHubPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val status by vm.bridge.status.collectAsState()
    val pending by vm.bridge.prompts.collectAsState()
    val groups = NavigationPolicy.featureGroups.map { group ->
        group to group.routes.mapNotNull { id -> pages.firstOrNull { it.id == id } }
            .filter { NavigationPolicy.matches(query, it.title, it.subtitle, it.id) }
    }.filter { it.second.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("简单设置，专注创作", style = MaterialTheme.typography.titleLarge)
                Text("按用途整理全部功能；常用操作留在侧边栏。",
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("settings-search"),
                placeholder = { Text("搜索模型、插件、终端或设置") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(22.dp)
            )
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                CodexEmptyState("没有匹配的功能", "试试功能名称或关键字。",
                    actionLabel = "清除搜索", onAction = { query = "" })
            }
        }
        items(groups, key = { it.first.title }) { (group, entries) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(group.title,
                    modifier = Modifier.padding(start = 8.dp, top = 9.dp),
                    color = Ink.muted, style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = RoundedCornerShape(25.dp),
                    color = Ink.canvas,
                    border = BorderStroke(1.dp, Ink.border.copy(alpha = .72f)),
                    shadowElevation = 2.dp,
                    tonalElevation = 0.dp
                ) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(
                                modifier = Modifier.padding(start = 74.dp, end = 18.dp),
                                color = Ink.border.copy(alpha = .45f), thickness = .5.dp)
                            SettingRouteRow(
                                page = entry, onClick = { navigate(entry.id) },
                                detail = when (entry.id) {
                                    "diagnostics" -> if (status == EngineStatus.Connected) "已连接" else "待检查"
                                    "security" -> "执行授权"
                                    "appearance" -> "典雅白"
                                    "usage", "limits" -> "查看数据"
                                    else -> ""
                                }
                            )
                        }
                    }
                }
            }
        }
        item(key = "footer") {
            Text("功能来源于本机 Codex 与当前可用服务，按实际状态展示。",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingRouteRow(page: Page, detail: String, onClick: () -> Unit) {
    val tone = when (page.section) {
        "能力与扩展" -> Ink.copper
        "项目与代码" -> Ink.jade
        "设置与账户" -> Color(0xFF5874A0)
        else -> Ink.copper
    }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("settings-${page.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 70.dp)
            .padding(horizontal = 17.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = Ink.canvas,
            border = BorderStroke(1.dp, tone.copy(alpha = .27f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(page.icon, contentDescription = null,
                    modifier = Modifier.size(22.dp), tint = tone)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(page.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(page.subtitle, color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (detail.isNotBlank()) {
            Text(detail, color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Ink.muted,
            modifier = Modifier.size(21.dp))
    }
}

/** 类似参考图的底部工具抽屉，只提供真实实现的操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceActionsSheet(
    onDismiss: () -> Unit,
    navigate: (String) -> Unit,
    onChooseImage: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.canvas,
        contentColor = Ink.parchment,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.border) }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("快捷工具", style = MaterialTheme.typography.titleLarge)
            Text("选择要打开的工具或工作内容。",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            if (onChooseImage != null) {
                Surface(shape = RoundedCornerShape(20.dp), color = Ink.canvas,
                    border = BorderStroke(1.dp, Ink.border)) {
                    Row(Modifier.fillMaxWidth().clickable {
                        onDismiss()
                        onChooseImage()
                    }.heightIn(min = 60.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Ink.copper)
                        Spacer(Modifier.width(12.dp))
                        Text("从相册或文件中选择图片", modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
                    }
                }
            }
            NavigationPolicy.quickToolRoutes.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { id ->
                        val item = pages.first { it.id == id }
                        Surface(
                            modifier = Modifier.weight(1f).testTag("quick-$id")
                                .clickable { onDismiss(); navigate(id) },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Ink.border),
                            color = Ink.canvas
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 15.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Icon(item.icon, null, tint = Ink.copper,
                                    modifier = Modifier.size(23.dp))
                                Text(item.title, maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { onDismiss(); navigate("settings") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Outlined.Settings, null)
                Spacer(Modifier.width(8.dp))
                Text("查看全部设置与功能")
            }
        }
    }
}
