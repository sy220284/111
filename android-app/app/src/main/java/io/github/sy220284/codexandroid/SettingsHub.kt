package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val listState = rememberLazyListState()
    val listScope = rememberCoroutineScope()
    var showModels by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var appearanceTarget by remember { mutableStateOf<String?>(null) }
    val status by vm.bridge.status.collectAsState()
    val data by vm.data.collectAsState()
    val isAccountLoaded = data["account"] != null
    val isSignedIn = data["account"]?.optJSONObject("account") != null
    val darkMode by UiPreferences.darkMode.collectAsState()
    val showTransfers = query.isBlank() || listOf("本地数据", "备份", "导入", "导出", "迁移", "恢复").any {
        it.contains(query.trim(), ignoreCase = true)
    }
    val showUpdate = query.isBlank() ||
        NavigationPolicy.matches(query, "应用更新", "检查并下载更新 安装 版本", "update")
    val groups = NavigationPolicy.featureGroups.map { group ->
        group to group.routes.mapNotNull { id ->
            pages.firstOrNull { it.id == id &&
                NavigationPolicy.showInSettingsGroup(id, query) &&
                NavigationPolicy.matches(query, it.title, it.subtitle, it.id) }
        }
    }.filter { (group, entries) ->
        entries.isNotEmpty() || ("update" in group.routes && (showUpdate || showTransfers))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-list"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
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
        if (query.isBlank()) item(key = "overview") {
            MaterialPanel(Modifier.fillMaxWidth().testTag("settings-overview")) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 51.dp)
                        .clickable(role = Role.Button) { navigate("account") }
                        .testTag("settings-account-overview"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = Ink.copper,
                        modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("账号", style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            !isAccountLoaded -> "等待账号状态"
                            isSignedIn -> "已登录"
                            else -> "未登录 · 点此配置"
                        }, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
                }
                HorizontalDivider(color = Ink.border.copy(alpha = .5f))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 51.dp)
                        .clickable(role = Role.Button) { navigate("diagnostics") }
                        .testTag("settings-engine-overview"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (status == EngineStatus.Connected)
                            Icons.Outlined.CheckCircle else Icons.Outlined.SyncProblem,
                        null, modifier = Modifier.size(24.dp),
                        tint = if (status == EngineStatus.Connected) Ink.jade else Ink.warning
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("运行服务", style = MaterialTheme.typography.titleMedium)
                        Text(when(status) {
                            EngineStatus.Connected -> "已连接"
                            EngineStatus.Starting -> "正在连接"
                            is EngineStatus.Unavailable -> "连接中断 · 查看处理方法"
                        }, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
                }
            }
        }
        if (groups.isEmpty()) {
            item { CodexEmptyState("未找到相关设置", "试试常用名称，例如字体、API Key 或日志。",
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
                            entries.forEach { entry ->
                                val spot = NavigationPolicy.matchedSettingSpot(entry.id, query)
                                SettingRouteRow(page = entry, detail = spot?.label
                                    ?: NavigationPolicy.matchedSettingTopic(entry.id, query) ?: when(entry.id) {
                                    "account" -> if (isAccountLoaded) (if (isSignedIn) "已登录" else "未登录") else "等待状态"
                                    "diagnostics" -> if (status == EngineStatus.Connected) "已连接" else "待处理"
                                    "security" -> "执行权限与审批"
                                    "environments" -> "本机或其他设备"
                                    "models" -> "选择任务模型"
                                    "appearance" -> if (darkMode) "深色" else "浅色"
                                    "plugins" -> "安装与管理"
                                    else -> ""
                                }) {
                                    SettingsDeepLink.select(entry.id, spot?.target)
                                    when(entry.id) {
                                        "models" -> showModels = true
                                        "appearance" -> {
                                            appearanceTarget = spot?.target
                                            showAppearance = if (spot != null) true else !showAppearance
                                            if (spot == null) {
                                                val groupIndex = groups.indexOfFirst { (group, _) ->
                                                    "appearance" in group.routes
                                                }
                                                if (groupIndex >= 0) listScope.launch {
                                                    listState.animateScrollToItem(
                                                        (if (query.isBlank()) 2 else 1) + groupIndex)
                                                }
                                            }
                                        }
                                        "about" -> showAbout = true
                                        else -> navigate(entry.id)
                                    }
                                }
                                if (entry.id == "appearance" && showAppearance) {
                                    HorizontalDivider(color = Ink.border)
                                    AppearanceInlineControls(focusTarget = appearanceTarget)
                                }
                            }
                        }
                    }
                }
                if ("update" in group.routes) {
                    if (showUpdate) AppUpdateCard(autoExpand =
                        query.isNotBlank() && NavigationPolicy.matchedSettingSpot("update", query) != null)
                    if (showTransfers) LocalDataTransferCard(vm)
                }
            }
        }
    }
    if (showModels) ModelChoiceSurface(vm) { showModels = false }
    if (showAbout) AboutInfoSheet(vm) { showAbout = false }
}

@Composable
private fun SettingRouteRow(page: Page, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("settings-route-${page.id}")
            .codexPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(page.icon, null, modifier = Modifier.size(22.dp), tint = Ink.muted)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(page.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (detail.isNotBlank()) detail else page.subtitle,
                color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted,
            modifier = Modifier.size(19.dp))
    }
}

@Composable
fun AppearanceInlineControls(focusTarget: String? = null) {
    val themeRequester = remember { BringIntoViewRequester() }
    val compactRequester = remember { BringIntoViewRequester() }
    val shadowRequester = remember { BringIntoViewRequester() }
    val motionRequester = remember { BringIntoViewRequester() }
    val fontRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(focusTarget) {
        when (focusTarget) {
            "theme" -> themeRequester.bringIntoView()
            "compact" -> compactRequester.bringIntoView()
            "shadow" -> shadowRequester.bringIntoView()
            "motion" -> motionRequester.bringIntoView()
            "font" -> fontRequester.bringIntoView()
        }
    }
    val compact by UiPreferences.compact.collectAsState()
    val softMaterial by UiPreferences.softMaterial.collectAsState()
    val lessMotion by UiPreferences.reducedMotion.collectAsState()
    val fontScale by UiPreferences.fontScale.collectAsState()
    val darkMode by UiPreferences.darkMode.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("界面配色", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.bringIntoViewRequester(themeRequester))
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
        Box(Modifier.bringIntoViewRequester(compactRequester)) {
            AppearanceSettingToggle("紧凑布局", "减少间距，优先显示任务内容", compact, UiPreferences::setCompact)
        }
        Box(Modifier.bringIntoViewRequester(shadowRequester)) {
            AppearanceSettingToggle("柔和阴影", "白底细边框与轻阴影分层", softMaterial, UiPreferences::setSoftMaterial)
        }
        Box(Modifier.bringIntoViewRequester(motionRequester)) {
            AppearanceSettingToggle("减少动效", "保留静态反馈，关闭多余位移", lessMotion, UiPreferences::setReducedMotion)
        }
        Text("文字大小", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 6.dp).bringIntoViewRequester(fontRequester)
                .testTag("appearance-font-heading"))
        Row(Modifier.fillMaxWidth().testTag("appearance-font-controls"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
