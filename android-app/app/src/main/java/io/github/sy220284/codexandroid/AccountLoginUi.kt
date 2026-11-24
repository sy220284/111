package io.github.sy220284.codexandroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Codex account connection workflow. The core owns credentials and OAuth.
 * Each visual step is driven by a real login response, never a sample code.
 */
@Composable
fun AccountPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val loginBusy by vm.loginBusy.collectAsState()
    val browserUrl by vm.accountLoginUrl.collectAsState()
    val deviceCode by vm.deviceLoginCode.collectAsState()
    val deviceUrl by vm.deviceLoginUrl.collectAsState()
    val loginId by vm.activeLoginId.collectAsState()
    val payload = data["account"]
    val identity = payload?.optJSONObject("account")
    val connected = identity != null
    val name = identity?.optString("email").orEmpty().ifBlank { "已连接的 Codex 账号" }
    val authMode = identity?.optString("type").orEmpty().ifBlank {
        payload?.optString("authMode").orEmpty()
    }
    val pending = loginId.isNotBlank()
    val devicePending = pending && deviceCode.isNotBlank() && deviceUrl.startsWith("https://")
    val browserPending = pending && browserUrl.startsWith("https://")
    var showRaw by remember { mutableStateOf(false) }
    var copied by remember(deviceCode) { mutableStateOf(false) }
    var logoutConfirm by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    val openAuthorization: (String) -> Unit = { url ->
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (failure: ActivityNotFoundException) {
            vm.errors.value = vm.errors.value + ("account" to "未找到可打开授权页面的浏览器")
        }
    }

    ScreenColumn {
        SectionIntro("账号与身份")

        if (!connected && !pending) {
            Text("选择登录方式", style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthMethodTile(
                            "设备码登录",
                            "复制一次性代码，在浏览器确认",
                            CodexGlyph.PhoneAndroid,
                            enabled = !loginBusy,
                            emphasized = true,
                            onClick = vm::startDeviceLogin,
                            modifier = Modifier.weight(1f)
                        )
                        AuthMethodTile(
                            "浏览器登录",
                            "通过浏览器完成 ChatGPT 授权",
                            CodexGlyph.OpenInNew,
                            enabled = !loginBusy,
                            emphasized = false,
                            onClick = vm::startLogin,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthMethodTile(
                            "设备码登录",
                            "复制一次性代码，在浏览器确认",
                            CodexGlyph.PhoneAndroid,
                            enabled = !loginBusy,
                            emphasized = true,
                            onClick = vm::startDeviceLogin,
                            modifier = Modifier.fillMaxWidth()
                        )
                        AuthMethodTile(
                            "浏览器登录",
                            "通过浏览器完成 ChatGPT 授权",
                            CodexGlyph.OpenInNew,
                            enabled = !loginBusy,
                            emphasized = false,
                            onClick = vm::startLogin,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }


        MaterialPanel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = Ink.canvas,
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp,
                            if (connected) Ink.jade.copy(alpha = .35f) else Ink.border)
                    ) {
                        CodexIcon(
                            if (connected) CodexGlyph.Security else CodexGlyph.AccountCircle,
                            contentDescription = null,
                            tint = if (connected) Ink.jade else Ink.copper,
                            modifier = Modifier.padding(10.dp).size(21.dp)
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("账号状态", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                        Text(
                            if (connected) name else if (payload == null) "尚未读取账号"
                                else "尚未登录",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = Ink.canvas,
                        shape = RoundedCornerShape(99.dp),
                        border = BorderStroke(1.dp,
                            if (connected) Ink.jade.copy(alpha = .33f) else Ink.border)
                    ) {
                        Text(
                            if (connected) "已连接" else "未连接",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connected) Ink.jade else Ink.muted
                        )
                    }
                }
                if (connected && authMode.isNotBlank()) {
                    Text("登录方式 · " + PresentationPolicy.accountAuthMethod(authMode), color = Ink.muted,
                        style = MaterialTheme.typography.labelMedium)
                }
                DividerLine()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.refresh("account", "account/read") },
                        enabled = !loading.contains("account"),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        CodexIcon(CodexGlyph.Retry, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("刷新账号状态", maxLines = 1)
                    }
                    if (connected) {
                        TextButton(
                            onClick = { logoutConfirm = true },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("退出账号") }
                    }
                }
            }
        }


        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("账号与额度", style = MaterialTheme.typography.titleMedium)
            Text("登录状态、使用记录和额度窗口来自同一账号，可从此处集中查看。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showSessions = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                CodexIcon(CodexGlyph.PeopleOutline, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("账号与工作区切换（实验）")
            }
            OutlinedButton(onClick = { navigate("usage") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("account-usage-limits")) {
                CodexIcon(CodexGlyph.Insights, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("查看用量与额度", modifier = Modifier.weight(1f))
                CodexIcon(CodexGlyph.ChevronRight, null, modifier = Modifier.size(18.dp))
            }
            AtlasSettingsActionRow("管理模型服务商", "配置可用模型与连接方式",
                CodexGlyph.Settings, onClick = { navigate("providers") })
        }
        errors["account"]?.let { CodexNotice(it, NoticeTone.Warning) }

        CodexReveal(visible = loginBusy) {
            AtlasOperationProgress(
                if (pending) "正在处理授权操作…" else "正在向 Codex 核心请求授权信息…"
            )
        }

        CodexReveal(visible = pending) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        CodexIcon(CodexGlyph.Security, null, tint = Ink.copper,
                            modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("授权进行中", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (devicePending) "设备验证码已生成"
                                else if (browserPending) "等待浏览器确认"
                                else "等待授权信息",
                                color = Ink.muted, style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (devicePending) {
                        DividerLine()
                        Text("一次性设备验证码", color = Ink.muted,
                            style = MaterialTheme.typography.labelMedium)
                        Surface(
                            color = Ink.recessed,
                            border = BorderStroke(1.dp, Ink.copper.copy(alpha = .38f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        deviceCode,
                                        modifier = Modifier.weight(1f, fill = false),
                                        color = Ink.parchment,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(deviceCode))
                                        copied = true
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    CodexIcon(
                                        if (copied) CodexGlyph.Check else CodexGlyph.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (copied) "已复制" else "复制验证码")
                                }
                            }
                        }
                        Text("1. 复制验证码   2. 打开授权页面   3. 输入验证码并确认",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.muted)
                        OutlinedButton(
                            onClick = { openAuthorization(deviceUrl) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            enabled = !loginBusy
                        ) {
                            CodexIcon(CodexGlyph.OpenInNew, null,
                                modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("打开验证码授权页")
                        }
                    } else if (browserPending) {
                        DividerLine()
                        Text("在浏览器完成授权，返回应用后可刷新账号状态。",
                            color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { openAuthorization(browserUrl) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            enabled = !loginBusy
                        ) {
                            CodexIcon(CodexGlyph.OpenInNew, null,
                                modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("在浏览器继续授权")
                        }
                    }
                    DividerLine()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.refresh("account", "account/read") },
                            enabled = !loading.contains("account"),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("刷新授权结果") }
                        TextButton(
                            onClick = vm::cancelLogin,
                            enabled = !loginBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("取消本次授权") }
                    }
                }
            }
        }

        OutlinedButton(onClick = { navigate("security") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            CodexIcon(CodexGlyph.Security, null)
            Spacer(Modifier.width(8.dp))
            Text("查看账号授权与安全设置")
        }
    }
    if (showSessions) AccountSessionsSheet(vm) { showSessions = false }
    if (logoutConfirm) CodexConfirmDialog(
        title = "退出 Codex 账号",
        message = "退出会清除本机 Codex 核心的登录状态。之后可以重新登录。",
        confirmLabel = "退出登录",
        destructive = true,
        onConfirm = {
            vm.rpc("account", "account/logout", JSONObject(),
                after = { vm.refresh("account", "account/read") })
            logoutConfirm = false
        },
        onDismiss = { logoutConfirm = false }
    )
}

/** Native Material interaction feedback; no independent animation loop or decorative effects. */
@Composable
private fun AuthMethodTile(
    title: String,
    subtitle: String,
    icon: CodexGlyph,
    enabled: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 86.dp),
        shape = RoundedCornerShape(18.dp),
        color = Ink.canvas,
        border = BorderStroke(1.dp,
            if (emphasized) Ink.copper.copy(alpha = .53f) else Ink.border.copy(alpha = .72f))
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = Ink.elevated, shape = RoundedCornerShape(12.dp)
            ) {
                CodexIcon(icon, null, tint = Ink.copper,
                    modifier = Modifier.padding(10.dp).size(21.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted,
                modifier = Modifier.size(19.dp))
        }
    }
}
