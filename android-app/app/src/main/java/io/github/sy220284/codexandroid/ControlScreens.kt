package io.github.sy220284.codexandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONTokener

object UiPreferences {
    private const val NAME = "visual-preferences"
    private var ctx: Context? = null
    val compact = MutableStateFlow(false)
    val reducedMotion = MutableStateFlow(false)
    fun initialize(context: Context) {
        ctx = context.applicationContext
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        compact.value = prefs.getBoolean("compact", false)
        reducedMotion.value = prefs.getBoolean("reducedMotion", false)
    }
    fun setCompact(value: Boolean) {
        compact.value = value
        ctx?.getSharedPreferences(NAME, Context.MODE_PRIVATE)?.edit()?.putBoolean("compact", value)?.apply()
    }
    fun setReducedMotion(value: Boolean) {
        reducedMotion.value = value
        ctx?.getSharedPreferences(NAME, Context.MODE_PRIVATE)?.edit()?.putBoolean("reducedMotion", value)?.apply()
    }
}

@Composable
fun ApprovalsPage(vm: CodexViewModel) {
    val prompts by vm.bridge.prompts.collectAsState()
    val errors by vm.errors.collectAsState()
    var reply by remember { mutableStateOf("") }
    ScreenColumn {
        SectionIntro("HUMAN / APPROVAL", "待审批操作", "明确呈现命令、文件变更、环境权限和外部工具请求。")
        Text("一次只批准当前确认的行为；会话授权与额外权限分开处理。", color = Ink.muted)
        errors["approvals"]?.let { ErrorText(it) }
        if (prompts.isEmpty()) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.GppGood, null, tint = Ink.jade)
                Text("目前没有等待授权的操作", style = MaterialTheme.typography.titleMedium)
                Text("当 Codex 请求运行命令或修改文件时，这里会出现审批卡片。", color = Ink.muted)
            }
        } else prompts.forEach { prompt ->
            val command = prompt.params.optString("command")
            val kind = prompt.params.optString("kind")
            val normal = prompt.method == "item/commandExecution/requestApproval" ||
                prompt.method == "item/fileChange/requestApproval"
            val humanOnly = kind == "humanCommand"
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AdminPanelSettings, null, tint = Ink.copper)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (prompt.method.contains("commandExecution")) "命令执行确认" else
                            if (prompt.method.contains("fileChange")) "文件变更确认" else
                                if (prompt.method.contains("permissions")) "额外权限申请" else "工具请求",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                    )
                    StatusPill("待决", false)
                }
                DividerLine()
                Text(prompt.params.optString("reason").ifBlank { "Codex 请求你确认下一步。" }, color = Ink.muted)
                if (command.isNotBlank()) Text(command, color = Ink.parchment, fontFamily = FontFamily.Monospace)
                prompt.params.optString("cwd").takeIf { it.isNotBlank() }?.let {
                    Text("工作目录：" + it, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                if (!normal || command.isBlank()) {
                    Text(prompt.params.toString(2), color = Ink.muted, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium)
                }
                if (prompt.method == "item/tool/requestUserInput") {
                    OutlinedTextField(reply, { reply = it }, label = { Text("回答问题") }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.answerApproval(prompt, "decline") }) {
                        Text("拒绝")
                    }
                    if (normal) {
                        Button(onClick = { vm.answerApproval(prompt, "accept") }) {
                            Text("仅本次允许")
                        }
                        if (!humanOnly) {
                            TextButton(onClick = { vm.answerApproval(prompt, "acceptForSession") }) {
                                Text("本会话允许")
                            }
                        }
                    } else if (prompt.method == "item/tool/requestUserInput") {
                        Button(onClick = { vm.answerApproval(prompt, "accept", reply) }, enabled = reply.isNotBlank()) {
                            Text("提交回答")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountPage(vm: CodexViewModel) {
    val ctx = LocalContext.current
    val models by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loginUrl by vm.accountLoginUrl.collectAsState()
    val account = models["account"]
    var logoutConfirm by remember { mutableStateOf(false) }
    ScreenColumn {
        SectionIntro("AUTH / IDENTITY", "账号与身份", "账号资料保存在 Codex 自己的本机配置中。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(42.dp), tint = Ink.copper)
            Text("账号信息", style = MaterialTheme.typography.titleLarge)
            Text(account?.optJSONObject("account")?.optString("email") ?: "尚未读取账号", color = Ink.muted)
            Text(account?.optString("authMode") ?: "", color = Ink.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startLogin() }) { Text("登录 ChatGPT") }
                OutlinedButton(onClick = { vm.refresh("account", "account/read") }) { Text("刷新状态") }
            }
            if (loginUrl.startsWith("https://") || loginUrl.startsWith("http://")) {
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                }) {
                    Icon(Icons.Outlined.OpenInBrowser, null)
                    Spacer(Modifier.width(6.dp))
                    Text("在浏览器继续授权")
                }
            }
            TextButton(onClick = { logoutConfirm = true }) { Text("退出当前账号") }
        }
        errors["account"]?.let { ErrorText(it) }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("实际账号详情", style = MaterialTheme.typography.titleMedium)
            Text(account?.toString(2) ?: "连接引擎后读取", color = Ink.muted,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (logoutConfirm) AlertDialog(onDismissRequest = { logoutConfirm = false },
        title = { Text("退出 Codex 账号") },
        text = { Text("将清除 Codex 运行核心所持有的本机登录状态。") },
        confirmButton = { TextButton(onClick = {
            vm.rpc("account", "account/logout", JSONObject(), after = { vm.refresh("account", "account/read") })
            logoutConfirm = false
        }) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = { logoutConfirm = false }) { Text("取消") } })
}

@Composable
fun ConfigPage(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    var path by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<Any?>(null) }
    ScreenColumn {
        SectionIntro("RUNTIME / PREFERENCES", "引擎配置", "使用官方 config/read 与 config/value/write，配置直接写入 Codex 本机设置。")
        Row {
            Button(onClick = { vm.refresh("config", "config/read", JSONObject().put("includeLayers", true)) }) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("读取生效配置")
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("编辑配置项", style = MaterialTheme.typography.titleMedium)
            Text("每次修改一个键。值采用 JSON 格式，如 true、15 或带双引号的字符串。", color = Ink.muted)
            OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("键路径，如 approval_policy") }, singleLine = true)
            OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), label = { Text("JSON 值") },
                placeholder = { Text("\"on-request\"") }, minLines = 2)
            Button(onClick = {
                try {
                    require(path.isNotBlank() && value.isNotBlank()) { "请填写键路径和值" }
                    parsed = JSONTokener(value).nextValue()
                    showConfirm = true
                } catch (failure: Exception) {
                    vm.errors.value = vm.errors.value + ("config" to (failure.message ?: "JSON 值无效"))
                }
            }) { Text("检查并保存") }
        }
        errors["config"]?.let { ErrorText(it) }
        data["config"]?.let {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("当前生效配置", style = MaterialTheme.typography.titleMedium)
                Text(it.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (showConfirm) AlertDialog(onDismissRequest = { showConfirm = false }, title = { Text("保存 Codex 配置") },
        text = { Text("键：" + path + "\n值：" + value + "\n\n此操作将修改本机 config.toml。") },
        confirmButton = { TextButton(onClick = {
            vm.rpc("config", "config/value/write", JSONObject().put("keyPath", path)
                .put("value", parsed).put("mergeStrategy", "replace"),
                after = { vm.refresh("config", "config/read") })
            showConfirm = false
        }) { Text("确认写入") } },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } })
}

@Composable
fun SecurityPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    var approval by remember { mutableStateOf("on-request") }
    var sandbox by remember { mutableStateOf("workspace-write") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh("security", "configRequirements/read") }
    ScreenColumn {
        SectionIntro("TRUST / EXECUTION", "运行权限与安全", "操作前明确可访问范围及何时需要授权。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("审批方式", style = MaterialTheme.typography.titleMedium)
            listOf("untrusted" to "仅执行可信操作", "on-request" to "需要时询问", "never" to "不申请额外批准").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { approval = v }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(approval == v, onClick = { approval = v }); Text(label)
                }
            }
            DividerLine()
            Text("工作区隔离策略", style = MaterialTheme.typography.titleMedium)
            listOf("read-only" to "只读", "workspace-write" to "允许工作区写入").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { sandbox = v }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(sandbox == v, onClick = { sandbox = v }); Text(label)
                }
            }
            Button(onClick = { confirm = true }) { Text("保存权限设置") }
            Text("Android 本身的应用沙箱仍适用；网络限制还需由操作系统环境实际验证。", color = Ink.muted)
        }
        OutlinedButton(onClick = { navigate("approvals") }) {
            Icon(Icons.Outlined.GppGood, null); Spacer(Modifier.width(8.dp)); Text("查看待审批操作")
        }
        errors["security"]?.let { ErrorText(it) }
        data["security"]?.let {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("管理员要求与受限策略", style = MaterialTheme.typography.titleMedium)
                Text(it.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("更新运行策略") },
        text = { Text("审批方式：" + approval + "\n隔离方式：" + sandbox) },
        confirmButton = { TextButton(onClick = {
            val edits = org.json.JSONArray()
                .put(JSONObject().put("keyPath", "approval_policy").put("value", approval).put("mergeStrategy", "replace"))
                .put(JSONObject().put("keyPath", "sandbox_mode").put("value", sandbox).put("mergeStrategy", "replace"))
            vm.rpc("security", "config/batchWrite", JSONObject().put("edits", edits),
                after = { vm.refresh("config", "config/read") })
            confirm = false
        }) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
fun AppearancePage() {
    val compact by UiPreferences.compact.collectAsState()
    val lessMotion by UiPreferences.reducedMotion.collectAsState()
    ScreenColumn {
        SectionIntro("DESIGN / SURFACES", "外观与交互", "深石墨界面、暖铜操作重点、柔雾层次与精简动效。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("界面材料", style = MaterialTheme.typography.titleMedium)
            Text("石墨雾面 / 细金属边 / 低反差分层", color = Ink.muted)
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("紧凑信息密度")
                    Text("缩小面板间距，让代码和数据占据更多空间", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                Switch(compact, onCheckedChange = UiPreferences::setCompact)
            }
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("减弱页面动效")
                    Text("缩短或关闭转场动画，保留状态反馈", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                Switch(lessMotion, onCheckedChange = UiPreferences::setReducedMotion)
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("颜色职责", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill("主要操作", true)
                StatusPill("风险确认", false)
            }
            Text("冷灰作为主要承载面；暖铜只用于执行入口、活动项和关键选择。", color = Ink.muted)
        }
    }
}

@Composable
fun AboutPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsState()
    ScreenColumn {
        SectionIntro("CODEX / OPEN SOURCE", "关于 Codex Android", "官方运行核心 + 原生 Android 操作界面")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("0.1.0 · Android 29+", style = MaterialTheme.typography.titleLarge)
            Text("上游官方项目：openai/codex", color = Ink.muted)
            Text("本项目仓库：sy220284/111", color = Ink.muted)
            Text("运行核心：Codex app-server（独立本地进程）", color = Ink.muted)
            StatusPill(if (status == EngineStatus.Connected) "内核可用" else "需要检查内核", status == EngineStatus.Connected)
            DividerLine()
            Text("隐私与文件", style = MaterialTheme.typography.titleMedium)
            Text("工程文件和 Codex 配置默认存储于 Android 应用私有目录。网络请求由 Codex 核心和用户启用的插件处理。账号密钥由官方运行核心管理。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            Text("源码来源的 Apache-2.0 许可及 NOTICE 保存在 upstream/codex/ 目录。", color = Ink.muted)
        }
    }
}
