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
                placeholder = { Text("搜索账号、设置或扩展") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, "清除搜索")
                    }
                }, singleLine = true, shape = RoundedCornerShape(22.dp))
        }
        if (groups.isEmpty()) {
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
                                    "appearance" -> if (UiPreferences.darkMode.value) "深色工作台" else "典雅白"
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
                    LocalDataTransferCard(vm)
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
    val fontScale by UiPreferences.fontScale.collectAsState()
    val darkMode by UiPreferences.darkMode.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("界面配色", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemePreviewCard(
                title = "典雅白", background = Color(0xFFFFFFFF),
                foreground = Color(0xFF263139), accent = Color(0xFFC8483E),
                selected = !darkMode, modifier = Modifier.weight(1f)
            ) { UiPreferences.setDarkMode(false) }
            ThemePreviewCard(
                title = "深色工作台", background = Color(0xFF111A22),
                foreground = Color(0xFFF0F2F3), accent = Color(0xFFFF9386),
                selected = darkMode, modifier = Modifier.weight(1f)
            ) { UiPreferences.setDarkMode(true) }
        }
        AppearanceSettingToggle("紧凑布局", "减少间距，优先显示任务内容", compact, UiPreferences::setCompact)
        AppearanceSettingToggle("柔和阴影", "白底细边框与轻阴影分层", softMaterial, UiPreferences::setSoftMaterial)
        AppearanceSettingToggle("减少动效", "保留静态反馈，关闭多余位移", lessMotion, UiPreferences::setReducedMotion)
        Text("文字大小", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(.9f to "较小", 1f to "标准", 1.125f to "较大", 1.25f to "最大")
                .forEach { (scale, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(fontScale - scale) < .01f,
                        onClick = { UiPreferences.setFontScale(scale) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
        }
        Text("文字大小会随系统字体设置叠加调整。",
            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ThemePreviewCard(
    title: String, background: Color, foreground: Color, accent: Color,
    selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Surface(
        modifier = modifier.heightIn(min = 104.dp)
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp), color = background,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) accent else foreground.copy(alpha = .24f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(title, color = foreground, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Outlined.CheckCircle, "已选择", tint = accent,
                    modifier = Modifier.size(17.dp))
            }
            Box(Modifier.fillMaxWidth(.82f).height(5.dp)
                .background(foreground.copy(alpha = .56f), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(.60f).height(5.dp)
                .background(foreground.copy(alpha = .25f), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(.42f).height(5.dp)
                .background(accent.copy(alpha = .80f), RoundedCornerShape(4.dp)))
        }
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

/** 文件和任务扩展入口统一使用一列明确操作，输入区模式采用参考截图的圆形图标列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceActionsSheet(
    onDismiss: () -> Unit, navigate: (String) -> Unit, hasThread: Boolean = false,
    imageCount: Int = 0, importing: Boolean = false, onPickImage: (() -> Unit)? = null
) {
    fun openTool(route: String) {
        // 先完成关闭，再交由宿主处理真实路由，避免多张浮层同时出现。
        onDismiss()
        navigate(route)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = {
            if (onPickImage == null) BottomSheetDefaults.DragHandle(color = Ink.border)
        }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 19.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(if (onPickImage == null) "工作区工具" else "添加附件",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 7.dp, top = 8.dp, bottom = 8.dp))
            if (onPickImage != null) {
                val imageAllowed = !importing &&
                    imageCount < LocalImageAttachments.MAX_FILES
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .testTag("attachment-pick-image")
                        .clickable(enabled = imageAllowed) {
                            onDismiss()
                            onPickImage()
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = Ink.canvas
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).background(Ink.elevated, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null,
                                tint = if (imageAllowed) Ink.parchment else Ink.muted,
                                modifier = Modifier.size(23.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Text("照片", style = MaterialTheme.typography.bodyLarge,
                            color = if (imageAllowed) Ink.parchment else Ink.muted,
                            modifier = Modifier.weight(1f))
                        if (!imageAllowed) {
                            Text(if (importing) "导入中" else "已达三张上限",
                                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            val routes = if (onPickImage == null) NavigationPolicy.quickToolRoutes
                else listOf("files","terminal","diff","review","goals","plugins","modes")
            routes.forEach { id ->
                val item = pages.first { it.id == id }
                val allowed = !UiFeatureCatalog.requiresThread(id) || hasThread
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("quick-$id")
                        .clickable { openTool(id) },
                    shape = RoundedCornerShape(16.dp),
                    color = Ink.canvas
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).background(Ink.elevated, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, tint = Ink.parchment,
                                modifier = Modifier.size(23.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(item.title, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f), color = Ink.parchment)
                        if (!allowed) {
                            Text("先选会话", style = MaterialTheme.typography.labelMedium,
                                color = Ink.muted)
                        } else {
                            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (!hasThread) {
                Text("选择历史会话后可使用代码审查、目标和队列工具。",
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
