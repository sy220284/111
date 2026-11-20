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
    val softMaterial = MutableStateFlow(true)
    fun initialize(context: Context) {
        ctx = context.applicationContext
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        compact.value = prefs.getBoolean("compact", false)
        reducedMotion.value = prefs.getBoolean("reducedMotion", false)
        softMaterial.value = prefs.getBoolean("softMaterial", true)
    }
    fun setCompact(value: Boolean) {
        compact.value = value
        ctx?.getSharedPreferences(NAME, Context.MODE_PRIVATE)?.edit()?.putBoolean("compact", value)?.apply()
    }
    fun setReducedMotion(value: Boolean) {
        reducedMotion.value = value
        ctx?.getSharedPreferences(NAME, Context.MODE_PRIVATE)?.edit()?.putBoolean("reducedMotion", value)?.apply()
    }
    fun setSoftMaterial(value: Boolean) {
        softMaterial.value = value
        ctx?.getSharedPreferences(NAME, Context.MODE_PRIVATE)?.edit()?.putBoolean("softMaterial", value)?.apply()
    }
}

@Composable
fun ApprovalsPage(vm: CodexViewModel) {
    val prompts by vm.bridge.prompts.collectAsState()
    val errors by vm.errors.collectAsState()
    ScreenColumn {
        SectionIntro("HUMAN / APPROVAL", "待审批操作", "运行核心请求人工审批时，逐项确认影响范围与风险。")
        errors["approvals"]?.let { ErrorText(it) }
        if (prompts.isEmpty()) {
            CodexEmptyState("目前没有等待授权的操作",
                "当任务需要执行命令、修改文件或申请权限时，确认请求会显示在这里。",
                icon = Icons.Outlined.GppGood)
        }
        prompts.forEach { prompt ->
            key(prompt.id.toString()) { ApprovalRequestCard(vm, prompt) }
        }
    }
}

@Composable
private fun ApprovalRequestCard(vm: CodexViewModel, prompt: ServerPrompt) {
    val method = prompt.method
    if (method == "mcpServer/elicitation/request") {
        McpElicitationRequestCard(vm, prompt)
        return
    }
    val command = prompt.params.optString("command")
    val kind = prompt.params.optString("kind")
    val normal = method == "item/commandExecution/requestApproval" ||
        method == "item/fileChange/requestApproval"
    val permission = method == "item/permissions/requestApproval"
    val question = method == "item/tool/requestUserInput"
    val answers = remember(prompt.id.toString()) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var grantSession by remember(prompt.id.toString()) { mutableStateOf(false) }
    var grantPermissions by remember(prompt.id.toString()) { mutableStateOf(false) }
    MaterialPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AdminPanelSettings, null, tint = Ink.copper)
            Spacer(Modifier.width(12.dp))
            Text(
                when (method) {
                    "item/commandExecution/requestApproval" -> "命令执行确认"
                    "item/fileChange/requestApproval" -> "文件变更确认"
                    "item/permissions/requestApproval" -> "额外权限申请"
                    "item/tool/requestUserInput" -> "Codex 请求回答"
                    else -> "外部工具授权请求"
                },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            StatusPill("等待决策", false)
        }
        DividerLine()
        val reason = prompt.params.optString("reason")
        if (reason.isNotBlank()) Text(reason, color = Ink.parchment)
        if (command.isNotBlank()) {
            Text("待执行命令", color = Ink.copper, style = MaterialTheme.typography.labelMedium)
            Text(command, fontFamily = FontFamily.Monospace, color = Ink.parchment)
        }
        prompt.params.optString("cwd").takeIf { it.isNotBlank() }?.let {
            Text("工作目录：" + it, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
        if (permission) {
            Text("正在请求以下权限", style = MaterialTheme.typography.titleMedium)
            Text((prompt.params.optJSONObject("permissions") ?: JSONObject()).toString(2),
                fontFamily = FontFamily.Monospace, color = Ink.parchment)
            Text("允许仅对当前任务轮次生效；拒绝不会授予任何新增权限。", color = Ink.muted)
        }
        if (question) {
            val items = prompt.params.optJSONArray("questions") ?: org.json.JSONArray()
            for (index in 0 until items.length()) {
                val q = items.optJSONObject(index) ?: continue
                val id = q.optString("id")
                val options = q.optJSONArray("options")
                Text(q.optString("header").ifBlank { "问题 " + (index + 1) },
                    color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                Text(q.optString("question"), style = MaterialTheme.typography.bodyMedium)
                if (options != null) {
                    for (opt in 0 until options.length()) {
                        val choice = options.optJSONObject(opt) ?: continue
                        val label = choice.optString("label")
                        Row(Modifier.fillMaxWidth().clickable { answers[id] = label },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = answers[id] == label, onClick = { answers[id] = label })
                            Column {
                                Text(label)
                                choice.optString("description").takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = answers[id] ?: "", onValueChange = { answers[id] = it },
                    label = { Text(if (options == null) "输入回答" else "或者输入其他回答") },
                    visualTransformation = if (q.optBoolean("isSecret"))
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(), singleLine = !q.optBoolean("isOther")
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.answerApproval(prompt, "decline") },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("拒绝") }
            if (normal) {
                Button(onClick = { vm.answerApproval(prompt, "accept") },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("仅本次允许") }
            } else if (permission) {
                Button(onClick = { grantPermissions = true },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("授权本轮") }
            } else if (question) {
                val questions = prompt.params.optJSONArray("questions") ?: org.json.JSONArray()
                val allAnswered = (0 until questions.length()).all {
                    val id = questions.optJSONObject(it)?.optString("id") ?: ""
                    answers[id]?.isNotBlank() == true
                }
                Button(onClick = { vm.answerUserInput(prompt, answers.toMap()) },
                    enabled = allAnswered && questions.length() > 0,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("提交全部回答") }
            }
        }
        if (normal && kind != "humanCommand") {
            TextButton(onClick = { grantSession = true }, modifier = Modifier.fillMaxWidth()) {
                Text("在当前会话内持续允许此类操作")
            }
        }
    }
    if (grantSession) CodexConfirmDialog(
        title = "确认本会话授权",
        message = "授权会覆盖当前会话中后续符合条件的同类操作，请核对执行内容和路径。",
        detail = prompt.params.toString(2),
        confirmLabel = "本会话允许",
        destructive = true,
        onConfirm = { grantSession = false; vm.answerApproval(prompt, "acceptForSession") },
        onDismiss = { grantSession = false }
    )
    if (grantPermissions) CodexConfirmDialog(
        title = "确认额外权限",
        message = "这些权限会授予当前任务轮次。请先检查权限范围。",
        detail = (prompt.params.optJSONObject("permissions") ?: JSONObject()).toString(2),
        confirmLabel = "授权本轮",
        destructive = true,
        onConfirm = { grantPermissions = false; vm.answerApproval(prompt, "accept") },
        onDismiss = { grantPermissions = false }
    )
}

@Composable
fun AccountPage(vm: CodexViewModel) {
    val ctx = LocalContext.current
    val models by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loginUrl by vm.accountLoginUrl.collectAsState()
    val deviceCode by vm.deviceLoginCode.collectAsState()
    val deviceUrl by vm.deviceLoginUrl.collectAsState()
    val activeLoginId by vm.activeLoginId.collectAsState()
    val account = models["account"]
    var logoutConfirm by remember { mutableStateOf(false) }
    ScreenColumn {
        SectionIntro("AUTH / IDENTITY", "账号与身份", "账号资料保存在 Codex 自己的本机配置中。")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本机账号与授权", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refresh("account", "account/read") }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("刷新账号状态")
            }
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(42.dp), tint = Ink.copper)
            Text("账号信息", style = MaterialTheme.typography.titleLarge)
            Text(account?.optJSONObject("account")?.optString("email") ?: "尚未读取账号", color = Ink.muted)
            Text(account?.optString("authMode") ?: "", color = Ink.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startDeviceLogin() }, modifier = Modifier.weight(1f)) {
                    Text("设备码登录", maxLines = 1)
                }
                OutlinedButton(onClick = { vm.startLogin() }, modifier = Modifier.weight(1f)) {
                    Text("浏览器登录", maxLines = 1)
                }
            }
            if (deviceCode.isNotBlank() && deviceUrl.startsWith("https://")) {
                DividerLine()
                Text("设备验证码", color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                Text(deviceCode, style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace)
                Text("在授权页面输入此代码，完成后返回应用刷新账号状态。",
                    color = Ink.muted)
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deviceUrl)))
                }) {
                    Icon(Icons.Outlined.OpenInBrowser, null)
                    Spacer(Modifier.width(6.dp))
                    Text("打开验证码授权页")
                }
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
            if (activeLoginId.isNotBlank()) {
                TextButton(onClick = { vm.cancelLogin() }) { Text("取消本次授权") }
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
    if (logoutConfirm) CodexConfirmDialog(
        title = "退出 Codex 账号",
        message = "退出将清除 Codex 运行核心的本机登录状态。之后可以重新登录。",
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
    if (showConfirm) CodexConfirmDialog(
        title = "保存 Codex 配置",
        message = "本次修改会写入本机 config.toml。请核对键和值。",
        detail = "键：$path\n值：$value",
        confirmLabel = "确认写入",
        onConfirm = {
            vm.rpc("config", "config/value/write", JSONObject().put("keyPath", path)
                .put("value", parsed).put("mergeStrategy", "replace"),
                after = { vm.refresh("config", "config/read") })
            showConfirm = false
        },
        onDismiss = { showConfirm = false }
    )
}

@Composable
fun SecurityPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val current = data["config"]?.optJSONObject("config")
    val requirements = data["securityRequirements"]
    val effectiveApproval = current?.optString("approvalPolicy").orEmpty()
    val effectiveSandbox = current?.optString("sandboxMode").orEmpty()
    var draftApproval by remember { mutableStateOf<String?>(null) }
    var draftSandbox by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val selectedApproval = draftApproval ?: effectiveApproval
    val selectedSandbox = draftSandbox ?: effectiveSandbox
    val hasChanges = current != null && (
        (draftApproval != null && draftApproval != effectiveApproval) ||
        (draftSandbox != null && draftSandbox != effectiveSandbox)
    )

    LaunchedEffect(Unit) {
        vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
        vm.refresh("securityRequirements", "configRequirements/read")
    }

    ScreenColumn {
        SectionIntro("TRUST / EXECUTION", "运行权限与安全", "先读取 Codex 实际生效设置，再明确修改审批与文件访问范围。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("当前生效权限", style = MaterialTheme.typography.titleMedium)
            Text("审批策略：" + effectiveApproval.ifBlank { "等待引擎读取" }, color = Ink.muted)
            Text("隔离策略：" + effectiveSandbox.ifBlank { "等待引擎读取" }, color = Ink.muted)
            DividerLine()
            Text("审批方式", style = MaterialTheme.typography.titleMedium)
            listOf("untrusted" to "只执行可信操作", "on-request" to "每当需要时请求批准",
                "never" to "无需请求授权，由 Codex 自行按策略执行").forEach { (mode, title) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = current != null) { draftApproval = mode },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedApproval == mode, onClick = { draftApproval = mode },
                        enabled = current != null)
                    Text(title, modifier = Modifier.weight(1f))
                }
            }
            DividerLine()
            Text("工作区文件访问", style = MaterialTheme.typography.titleMedium)
            listOf("read-only" to "只读", "workspace-write" to "允许编辑工作区").forEach { (mode, title) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = current != null) { draftSandbox = mode },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedSandbox == mode, onClick = { draftSandbox = mode },
                        enabled = current != null)
                    Text(title)
                }
            }
            Button(onClick = { confirm = true }, enabled = hasChanges, modifier = Modifier.fillMaxWidth()) {
                Text("保存本次权限调整")
            }
            Text("Android 应用私有空间由系统隔离；Codex 的沙箱与网络策略仍由实际运行环境执行。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { navigate("approvals") }) {
            Icon(Icons.Outlined.GppGood, null)
            Spacer(Modifier.width(8.dp))
            Text("查看待审批操作")
        }
        errors["config"]?.let { ErrorText(it) }
        errors["securityRequirements"]?.let { ErrorText(it) }
        errors["securitySave"]?.let { ErrorText(it) }
        if (requirements != null) MaterialPanel(Modifier.fillMaxWidth()) {
            Text("组织限制与允许的策略", style = MaterialTheme.typography.titleMedium)
            Text(requirements.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted,
                style = MaterialTheme.typography.labelMedium)
        }
    }
    if (confirm) CodexConfirmDialog(
        title = "确认更新运行权限",
        message = "更改会影响后续命令审批及工作区文件访问。",
        detail = "审批：$effectiveApproval → $selectedApproval\n文件访问：$effectiveSandbox → $selectedSandbox",
        confirmLabel = "确认保存",
        destructive = true,
        onConfirm = {
            val edits = org.json.JSONArray()
            if (draftApproval != null && draftApproval != effectiveApproval) {
                edits.put(JSONObject().put("keyPath", "approval_policy")
                    .put("value", draftApproval).put("mergeStrategy", "replace"))
            }
            if (draftSandbox != null && draftSandbox != effectiveSandbox) {
                edits.put(JSONObject().put("keyPath", "sandbox_mode")
                    .put("value", draftSandbox).put("mergeStrategy", "replace"))
            }
            if (edits.length() > 0) {
                vm.rpc("securitySave", "config/batchWrite",
                    JSONObject().put("edits", edits).put("reloadUserConfig", true),
                    after = { vm.refresh("config", "config/read", JSONObject().put("includeLayers", true)) })
            }
            confirm = false
        },
        onDismiss = { confirm = false }
    )
}

@Composable
fun AppearancePage() {
    val compact by UiPreferences.compact.collectAsState()
    val lessMotion by UiPreferences.reducedMotion.collectAsState()
    val softMaterial by UiPreferences.softMaterial.collectAsState()
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
                    Text("柔雾层次材质")
                    Text("通过低对比渐层强调内容层级；关闭后使用纯色面板",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                Switch(softMaterial, onCheckedChange = UiPreferences::setSoftMaterial)
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
        CodexActionTile("交互预览", "点击可切换紧凑排版，查看触摸反馈", Icons.Outlined.TouchApp,
            onClick = { UiPreferences.setCompact(!compact) }, modifier = Modifier.fillMaxWidth())
        CodexNotice(if (lessMotion) "已关闭装饰性位移动画，状态仍会立即更新"
            else "页面转场与操作反馈已经启用", NoticeTone.Info)
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
