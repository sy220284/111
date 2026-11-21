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
import kotlinx.coroutines.launch

/** 设置只容纳长期配置和能力管理；任务操作由会话和工作区触发。 */
@Composable
fun SettingsHubPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    val status by vm.bridge.status.collectAsState()
    val groups = NavigationPolicy.featureGroups.map { group ->
        group to group.routes.mapNotNull { id ->
            pages.firstOrNull { it.id == id && NavigationPolicy.matches(
                query, it.title, it.subtitle, it.id) }
        }
    }.filter { it.second.isNotEmpty() ||
        (it.first.title == "通用与外观" && (query.isBlank() ||
            NavigationPolicy.matches(query, "应用更新", "检查并下载更新 安装 版本", "update"))) }
    val managed = NavigationPolicy.settingsRoutes.toSet()
    val hiddenMatches = if (query.isBlank()) emptyList() else
        pages.filter { it.id !in managed && it.id != "settings" &&
            NavigationPolicy.matches(query, it.title, it.subtitle, it.id) }
    val showUpdate = query.isBlank() ||
        NavigationPolicy.matches(query, "应用更新", "检查并下载更新 安装 版本", "update")
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("设置与管理", style = MaterialTheme.typography.titleLarge)
                Text("常用操作在对话和工作区；这里集中管理账号、扩展和主机。",
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item(key = "search") {
            OutlinedTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("settings-search"),
                placeholder = { Text("搜索配置、扩展或功能名称") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, "清除搜索")
                    }
                }, singleLine = true, shape = RoundedCornerShape(22.dp))
        }
        if (groups.isEmpty() && hiddenMatches.isEmpty()) {
            item { CodexEmptyState("未找到相关功能", "可以按名称或关键字重新查询。",
                actionLabel = "清除搜索", onAction = { query = "" }) }
        }
        items(groups, key = { it.first.title }) { (group, entries) ->
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(group.title, modifier = Modifier.padding(start = 8.dp, top = 9.dp),
                    color = Ink.muted, style = MaterialTheme.typography.titleMedium)
                if (entries.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(15.dp), color = Ink.canvas,
                        border = BorderStroke(1.dp, Ink.border),
                        shadowElevation = 0.dp, tonalElevation = 0.dp) {
                        Column {
                            entries.forEachIndexed { index, entry ->
                                if (index > 0) HorizontalDivider(
                                    modifier = Modifier.padding(start = 58.dp, end = 15.dp),
                                    color = Ink.border.copy(alpha = .46f), thickness = .5.dp)
                                SettingRouteRow(page = entry, detail = when(entry.id) {
                                    "diagnostics" -> if (status == EngineStatus.Connected) "已连接" else "待检查"
                                    "security" -> "执行授权"
                                    "models" -> "选择模型"
                                    "appearance" -> "典雅白"
                                    else -> if (entry.experimental) "实验" else ""
                                }) {
                                    when(entry.id) {
                                        "models" -> showModels = true
                                        "appearance" -> showAppearance = !showAppearance
                                        "about" -> showAbout = true
                                        else -> navigate(entry.id)
                                    }
                                }
                                if (entry.id == "appearance" && showAppearance) {
                                    HorizontalDivider(color = Ink.border)
                                    AppearanceInlineControls()
                                }
                            }
                        }
                    }
                }
                if (group.title == "通用与外观" && showUpdate) {
                    AppUpdateCard()
                }
            }
        }
        if (hiddenMatches.isNotEmpty()) {
            item(key = "search-extra-title") {
                Text("功能搜索结果", modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    style = MaterialTheme.typography.titleMedium, color = Ink.muted)
            }
            items(hiddenMatches, key = { "extra-" + it.id }) { entry ->
                SettingRouteRow(entry, detail = "前往功能") {
                    when(entry.id) {
                        "modes" -> navigate("modes")
                        "limits" -> navigate("limits")
                        "search" -> navigate("threads")
                        else -> navigate(entry.id)
                    }
                }
            }
        }
        item(key = "footer") {
            Text("运行状态与能力来自实际内核，实验功能按当前服务可用性执行。",
                modifier = Modifier.padding(vertical = 12.dp),
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (showModels) ModelChoiceSurface(vm) { showModels = false }
    if (showAbout) AboutInfoSheet(vm) { showAbout = false }
}

@Composable
private fun SettingRouteRow(page: Page, detail: String, onClick: () -> Unit) {
    val tone = when(page.section) {
        "能力与扩展" -> Ink.copper
        "项目与代码" -> Ink.jade
        else -> Ink.copper
    }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("settings-route-${page.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(modifier = Modifier.size(34.dp), shape = CircleShape,
            color = Ink.canvas, border = BorderStroke(1.dp, tone.copy(alpha = .33f))) {
            Box(contentAlignment = Alignment.Center) {
                Icon(page.icon, null, modifier = Modifier.size(19.dp), tint = tone)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(page.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(page.subtitle, color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (detail.isNotBlank() && page.id == "diagnostics") Text(detail, color = Ink.muted,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted,
            modifier = Modifier.size(21.dp))
    }
}

@Composable
fun AppearanceInlineControls() {
    val compact by UiPreferences.compact.collectAsState()
    val softMaterial by UiPreferences.softMaterial.collectAsState()
    val lessMotion by UiPreferences.reducedMotion.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AppearanceSettingToggle("紧凑布局", "减少间距，优先显示任务内容", compact, UiPreferences::setCompact)
        AppearanceSettingToggle("柔和阴影", "白底细边框与轻阴影分层", softMaterial, UiPreferences::setSoftMaterial)
        AppearanceSettingToggle("减少动效", "保留静态反馈，关闭多余位移", lessMotion, UiPreferences::setReducedMotion)
    }
}

@Composable
private fun AppearanceSettingToggle(
    title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
        WhiteSwitch(value, onCheckedChange = onChange)
    }
}

/** 与输入框附件菜单分离：这里只呈现工作区和当前会话工具。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceActionsSheet(
    onDismiss: () -> Unit, navigate: (String) -> Unit, hasThread: Boolean = false,
    imageCount: Int = 0, importing: Boolean = false, onPickImage: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun openTool(route: String) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            navigate(route)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.canvas,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.border) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (onPickImage == null) "工作区工具" else "任务工具与附件",
                style = MaterialTheme.typography.headlineSmall)
            Text("文件与终端随时可用；代码审查和任务队列需要选中会话。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            if (onPickImage != null) {
                Text("添加附件", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { scope.launch { sheetState.hide(); onDismiss(); onPickImage() } },
                    enabled = !importing && imageCount < LocalImageAttachments.MAX_FILES,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                        .testTag("attachment-pick-image")
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("从相册或文件选择图片")
                }
                if (imageCount >= LocalImageAttachments.MAX_FILES)
                    Text("最多添加三张图片，移除已有附件后可继续。",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                HorizontalDivider(color = Ink.border)
            }
            NavigationPolicy.quickToolRoutes.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { id ->
                        val item = pages.first { it.id == id }
                        val allowed = !UiFeatureCatalog.requiresThread(id) || hasThread
                        Surface(
                            modifier = Modifier.weight(1f).testTag("quick-$id")
                                .clickable { openTool(if(allowed) id else "threads") },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, if(allowed) Ink.border else Ink.copperDim),
                            color = Ink.canvas
                        ) {
                            Column(Modifier.padding(vertical = 15.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Icon(item.icon, null, tint = if (allowed) Ink.copper else Ink.muted,
                                    modifier = Modifier.size(23.dp))
                                Text(item.title, maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium)
                                if (!allowed) Text("先选会话",
                                    style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                            }
                        }
                    }
                }
            }
            if (!hasThread) OutlinedButton(onClick = { openTool("threads") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.History, null)
                Spacer(Modifier.width(8.dp))
                Text("选择会话后使用任务工具")
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
