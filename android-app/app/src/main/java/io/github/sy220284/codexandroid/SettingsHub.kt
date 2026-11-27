package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 设置只容纳长期配置和能力管理；任务操作由会话和工作区触发。 */
@Composable
fun SettingsHubPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val listState = rememberLazyListState()
    var showAbout by remember { mutableStateOf(false) }
    val status by vm.bridge.status.collectAsStateWithLifecycle()
    val data by vm.data.collectEntries("account")
    val isAccountLoaded = data["account"] != null
    val isSignedIn = data["account"]?.optJSONObject("account") != null
    val darkMode by UiPreferences.darkMode.collectAsStateWithLifecycle()
    val groups = remember { NavigationPolicy.featureGroups.map { group ->
        group to group.routes.mapNotNull { id ->
            pages.firstOrNull { it.id == id &&
                NavigationPolicy.showInSettingsGroup(id, "") }
        }
    }.filter { (group, entries) ->
        entries.isNotEmpty() || "update" in group.routes
    } }
    val detailRoutes = remember { setOf("environments", "features", "plugins", "mcp", "config") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-list"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "overview") {
            MaterialPanel(Modifier.fillMaxWidth().testTag("settings-overview")) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 51.dp)
                        .clickable(role = Role.Button) { navigate("account") }
                        .testTag("settings-account-overview"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CodexIcon(CodexGlyph.PersonOutline, null, tint = Ink.copper,
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
                    CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted)
                }
                HorizontalDivider(color = Ink.border.copy(alpha = .5f))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 51.dp)
                        .clickable(role = Role.Button) { navigate("diagnostics") }
                        .testTag("settings-engine-overview"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CodexIcon(
                        if (status == EngineStatus.Connected)
                            CodexGlyph.TaskComplete else CodexGlyph.SyncProblem,
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
                    CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted)
                }
            }
        }
        groups.forEach { (group, entries) ->
            item(key = "settings-group:" + group.title) {
                Text(group.title, modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 9.dp),
                    color = Ink.muted, style = MaterialTheme.typography.titleMedium)
            }
            itemsIndexed(entries, key = { _, entry -> "settings-route:" + entry.id }) { index, entry ->
                val top = if (index == 0) 15.dp else 0.dp
                val bottom = if (index == entries.lastIndex) 15.dp else 0.dp
                Surface(shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    color = Ink.canvas, border = BorderStroke(1.dp, Ink.border),
                    shadowElevation = 0.dp, tonalElevation = 0.dp) {
                    SettingRouteRow(page = entry, showDetail = entry.id in detailRoutes, detail = when(entry.id) {
                        "account" -> if (isAccountLoaded) (if (isSignedIn) "已登录" else "未登录") else "等待状态"
                        "diagnostics" -> if (status == EngineStatus.Connected) "已连接" else "待处理"
                        "environments" -> "本机或其他设备"
                        "models" -> "默认模型与推理偏好"
                        "appearance" -> if (darkMode) "深色" else "浅色"
                        "plugins" -> "安装与管理"
                        "features" -> "常用能力与全部内核功能"
                        "mcp" -> "外部工具连接"
                        "config" -> "高级运行设置"
                        else -> ""
                    }) {
                        SettingsDeepLink.select(entry.id, null)
                        when(entry.id) {
                            "about" -> showAbout = true
                            else -> navigate(entry.id)
                        }
                    }
                }
            }
            if ("update" in group.routes) {
                item(key = "settings-update") { Box(Modifier.padding(top = 9.dp)) { AppUpdateCard(vm = vm) } }
                item(key = "settings-transfer") { Box(Modifier.padding(top = 9.dp)) { LocalDataTransferCard(vm) } }
            }
        }
    }
    if (showAbout) AboutInfoSheet(vm) { showAbout = false }
}

@Composable
private fun SettingRouteRow(page: Page, detail: String, showDetail: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("settings-route-${page.id}")
            .codexPressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CodexIcon(page.icon, null, modifier = Modifier.size(22.dp), tint = Ink.muted)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(page.title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showDetail) {
                Text(if (detail.isNotBlank()) detail else page.subtitle,
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted,
            modifier = Modifier.size(19.dp))
    }
}

@Composable
fun AppearanceInlineControls(focusTarget: String? = null, modifier: Modifier = Modifier.padding(horizontal = 17.dp)) {
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
    val compact by UiPreferences.compact.collectAsStateWithLifecycle()
    val softMaterial by UiPreferences.softMaterial.collectAsStateWithLifecycle()
    val lessMotion by UiPreferences.reducedMotion.collectAsStateWithLifecycle()
    val fontScale by UiPreferences.fontScale.collectAsStateWithLifecycle()
    val darkMode by UiPreferences.darkMode.collectAsStateWithLifecycle()
    val largeTypography = codexEffectiveFontScale() >= 1.55f
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("界面配色", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 7.dp).bringIntoViewRequester(themeRequester))
        if (largeTypography) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemePreviewCard(
                    title = "典雅白", background = Color(0xFFFFFFFF),
                    foreground = Color(0xFF263139), accent = Color(0xFFC8483E),
                    selected = !darkMode, modifier = Modifier.fillMaxWidth()
                ) { UiPreferences.setDarkMode(false) }
                ThemePreviewCard(
                    title = "深色工作台", background = Color(0xFF111A22),
                    foreground = Color(0xFFF0F2F3), accent = Color(0xFFFF9386),
                    selected = darkMode, modifier = Modifier.fillMaxWidth()
                ) { UiPreferences.setDarkMode(true) }
            }
        } else {
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
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodexIcon(CodexGlyph.TouchApp, null, tint = Ink.muted, modifier = Modifier.size(20.dp))
            Text("布局与交互", style = MaterialTheme.typography.titleMedium)
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
                    CodexFilterChip(
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
                if (selected) CodexIcon(CodexGlyph.TaskComplete, "已选择", tint = accent,
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
