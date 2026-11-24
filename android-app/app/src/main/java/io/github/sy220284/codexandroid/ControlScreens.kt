package io.github.sy220284.codexandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    // 只持久保留 SharedPreferences，不保留任何 Activity 或 Context。
    private var storage: android.content.SharedPreferences? = null
    val compact = MutableStateFlow(false)
    val reducedMotion = MutableStateFlow(false)
    val softMaterial = MutableStateFlow(true)
    val fontScale = MutableStateFlow(1f)
    val darkMode = MutableStateFlow(false)

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        storage = prefs
        compact.value = prefs.getBoolean("compact", false)
        reducedMotion.value = prefs.getBoolean("reducedMotion", false)
        softMaterial.value = prefs.getBoolean("softMaterial", true)
        fontScale.value = prefs.getFloat("fontScale", 1f).coerceIn(.9f, 1.25f)
        darkMode.value = prefs.getBoolean("darkMode", false)
    }
    fun setCompact(value: Boolean) {
        compact.value = value
        storage?.edit()?.putBoolean("compact", value)?.apply()
    }
    fun setReducedMotion(value: Boolean) {
        reducedMotion.value = value
        storage?.edit()?.putBoolean("reducedMotion", value)?.apply()
    }
    fun setSoftMaterial(value: Boolean) {
        softMaterial.value = value
        storage?.edit()?.putBoolean("softMaterial", value)?.apply()
    }
    fun setDarkMode(value: Boolean) {
        darkMode.value = value
        storage?.edit()?.putBoolean("darkMode", value)?.apply()
    }
    fun setFontScale(value: Float) {
        val selected = value.coerceIn(.9f, 1.25f)
        fontScale.value = selected
        storage?.edit()?.putFloat("fontScale", selected)?.apply()
    }
}

@Composable
fun ApprovalsPage(vm: CodexViewModel) {
    val prompts by vm.bridge.prompts.collectAsState()
    val errors by vm.errors.collectAsState()
    val auditRecords by vm.approvalAudit.records.collectAsState()
    val auditError by vm.approvalAudit.error.collectAsState()
    ScreenColumn {
        SectionIntro("待审批操作", "运行核心请求人工审批时，逐项确认影响范围与风险。")
        errors["approvals"]?.let { ErrorText(it) }
        if (prompts.isEmpty()) {
            CodexEmptyState("目前没有等待授权的操作",
                "当任务需要执行命令、修改文件或申请权限时，确认请求会显示在这里。",
                icon = CodexGlyph.Security,
                artworkRes = R.drawable.ic_empty_approval)
        }
        if (prompts.isNotEmpty())
            Text("待处理 ${prompts.size} 项 · 请按顺序核对",
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        prompts.forEachIndexed { index, prompt ->
            StatusPill("第 ${index + 1}/${prompts.size} 项", false, StatusTone.Warning)
            key(prompt.id.toString()) { ApprovalRequestCard(vm, prompt) }
        }
        ApprovalAuditSection(auditRecords, auditError) { vm.approvalAudit.clear() }
    }
}

/** Same validated approval flow, directly accessible from the active conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalQuickSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val prompts by vm.bridge.prompts.collectAsState()
    ApprovalQuickSheetContent(vm, prompts, onDismiss)
}

/** Complete sheet bound to an explicit prompt snapshot for deterministic rendering. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalQuickSheetContent(
    vm: CodexViewModel, prompts: List<ServerPrompt>, onDismiss: () -> Unit
) {
    val auditRecords by vm.approvalAudit.records.collectAsState()
    val auditError by vm.approvalAudit.error.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.canvas, contentColor = Ink.parchment
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("待处理审批", style = MaterialTheme.typography.headlineSmall)
            Text("请根据具体命令、文件和权限范围作出决定。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            if (prompts.isEmpty()) {
                CodexEmptyState("当前审批已处理", "返回任务即可继续查看执行进展。",
                    icon = CodexGlyph.Security)
                OutlinedButton(onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("返回当前任务")
                }
            } else {
                Text("待处理 ${prompts.size} 项", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
                prompts.forEachIndexed { index, prompt ->
                    StatusPill("第 ${index + 1}/${prompts.size} 项", false, StatusTone.Warning)
                    key(prompt.id.toString()) { ApprovalRequestCard(vm, prompt) }
                }
            }
            if (auditRecords.isNotEmpty() || !auditError.isNullOrBlank()) {
                ApprovalAuditSection(auditRecords, auditError) { vm.approvalAudit.clear() }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun ApprovalRequestCard(vm: CodexViewModel, prompt: ServerPrompt) {
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
    val permissionScope = remember(prompt.id.toString()) {
        PresentationPolicy.permissionDetails(prompt.params.optJSONObject("permissions"))
    }
    val question = method == "item/tool/requestUserInput"
    val answers = remember(prompt.id.toString()) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var grantSession by remember(prompt.id.toString()) { mutableStateOf(false) }
    var grantPermissions by remember(prompt.id.toString()) { mutableStateOf(false) }
    var morePermissions by remember(prompt.id.toString()) { mutableStateOf(false) }
    var commandExpanded by remember(prompt.id.toString()) { mutableStateOf(false) }
    MaterialPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodexIcon(CodexGlyph.TaskApproval, null, tint = Ink.copper)
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
            Text(
                if (commandExpanded || command.length <= 240) command else command.take(240) + "…",
                fontFamily = FontFamily.Monospace, color = Ink.parchment
            )
            if (command.length > 240) TextButton(
                onClick = { commandExpanded = !commandExpanded }
            ) { Text(if (commandExpanded) "收起完整命令" else "查看完整命令") }
        }
        prompt.params.optString("cwd").takeIf { it.isNotBlank() }?.let {
            Text("工作区：" + PresentationPolicy.workspaceName(it),
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
        if (permission) {
            Text("正在请求以下权限", style = MaterialTheme.typography.titleMedium)
            permissionScope.forEach { description ->
                Text("· " + description, color = Ink.parchment,
                    style = MaterialTheme.typography.bodyMedium)
            }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!question) CodexSecondaryButton(
                onClick = { vm.answerApproval(prompt, ApprovalAction.Decline) },
                modifier = Modifier.weight(1f)
            ) { Text("拒绝") }
            if (normal) {
                CodexPrimaryButton(
                    onClick = { vm.answerApproval(prompt, ApprovalAction.AllowOnce) },
                    modifier = Modifier.weight(1.35f)
                ) { Text("仅本次允许") }
            } else if (permission) {
                CodexPrimaryButton(onClick = { grantPermissions = true },
                    modifier = Modifier.weight(1.35f)) { Text("授权本轮") }
            } else if (question) {
                val questions = prompt.params.optJSONArray("questions") ?: org.json.JSONArray()
                val allAnswered = (0 until questions.length()).all {
                    val id = questions.optJSONObject(it)?.optString("id") ?: ""
                    answers[id]?.isNotBlank() == true
                }
                val canSubmit = allAnswered && questions.length() > 0
                CodexPrimaryButton(onClick = { vm.answerUserInput(prompt, answers.toMap()) },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f).explainDisabled(!canSubmit,
                        "需要先回答全部问题才能提交")
                ) { Text("提交全部回答") }
            }
            if (normal && kind != "humanCommand") {
                Box {
                    IconButton(onClick = { morePermissions = true },
                        modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.MoreHoriz, contentDescription = "更多授权选项")
                    }
                    DropdownMenu(expanded = morePermissions, onDismissRequest = {
                        morePermissions = false
                    }) {
                        DropdownMenuItem(
                            text = { Text("在本会话持续允许") },
                            onClick = { morePermissions = false; grantSession = true }
                        )
                    }
                }
            }
        }
        if (question) Text("等待回答时可返回对话查看任务；停止任务可取消本次提问。",
            color = Ink.muted, style = MaterialTheme.typography.labelSmall)
    }
    if (grantSession) CodexConfirmDialog(
        title = "确认本会话授权",
        message = "授权会覆盖当前会话中后续符合条件的同类操作，请核对执行内容和路径。",
        detail = listOf(
            "操作：" + when {
                command.isNotBlank() -> command
                prompt.params.optString("reason").isNotBlank() -> prompt.params.optString("reason")
                else -> "当前文件或工具操作"
            },
            "工作区：" + PresentationPolicy.workspaceName(prompt.params.optString("cwd"))
        ).joinToString("\n"),
        confirmLabel = "本会话允许",
        destructive = true,
        onConfirm = { grantSession = false; vm.answerApproval(prompt, ApprovalAction.AllowSession) },
        onDismiss = { grantSession = false }
    )
    if (grantPermissions) CodexConfirmDialog(
        title = "确认额外权限",
        message = "这些权限会授予当前任务轮次。请先检查权限范围。",
        detail = permissionScope.joinToString("\n"),
        confirmLabel = "授权本轮",
        destructive = true,
        onConfirm = { grantPermissions = false; vm.answerApproval(prompt, ApprovalAction.GrantTurn) },
        onDismiss = { grantPermissions = false }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val engineStatus by vm.bridge.status.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("config") == "advanced") advanced = true
    }
    var path by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("text") }
    var booleanValue by rememberSaveable { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<Any?>(null) }
    val ready = data["config"] != null

    ScreenColumn {
        SectionIntro("引擎配置",
            "常用设置可直接在设置页管理；这里只处理需要手动填写的高级参数。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Settings, null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前配置", style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        ready -> "已读取本机配置"
                        "config" in loading -> "正在读取"
                        errors["config"] != null -> "读取失败"
                        else -> "未读取"
                    },
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                }
                IconButton(onClick = {
                    vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
                }, enabled = "config" !in loading) {
                    CodexIcon(CodexGlyph.Retry, "读取生效配置")
                }
            }
            if ("config" in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("配置保存在当前设备，修改成功后影响后续任务。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        }
        errors["config"]?.let { ErrorText(it) }
        if (!ready && "config" !in loading) {
            if (engineStatus != EngineStatus.Connected) {
                CodexRecoveryState("连接后可读取引擎配置",
                    "运行核心连接后可查看生效配置并编辑高级参数。",
                    "重新连接", { vm.connect() }, { navigate("diagnostics") })
            } else {
                CodexRecoveryState(
                    if (errors["config"] != null) "配置读取失败" else "配置尚未读取",
                    "可重新请求本机生效配置，读取后再修改。",
                    "重新读取", {
                        vm.refresh("config", "config/read",
                            JSONObject().put("includeLayers", true))
                    }, { navigate("diagnostics") })
            }
        }
        CodexDetailTrigger(
            label = "高级自定义配置",
            onClick = { advanced = true },
            modifier = Modifier.fillMaxWidth().testTag("config-advanced-toggle")
        )
        if (advanced) {
            CodexDetailSheet(
                title = "高级自定义配置",
                subtitle = "请核对设置项和值。保存前还需确认更改范围。",
                onDismiss = { advanced = false }
            ) {
                Text("适用于已经了解目标设置项的用户。修改前核对名称和值；每次只提交一项。",
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("设置项名称") }, singleLine = true,
                    placeholder = { Text("approval_policy") })
                Text("设置值类型", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    val types = listOf(
                        "text" to "文本", "boolean" to "开关",
                        "number" to "数字", "json" to "高级数据"
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.width(464.dp)) {
                        types.forEachIndexed { index, pair ->
                            SegmentedButton(
                                selected = kind == pair.first,
                                onClick = { kind = pair.first },
                                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                                label = { Text(pair.second) }
                            )
                        }
                    }
                }
                if (kind == "boolean") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (booleanValue) "已启用" else "已关闭",
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = booleanValue,
                            onCheckedChange = { booleanValue = it }
                        )
                    }
                } else {
                    OutlinedTextField(
                        value, { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(when (kind) {
                            "number" -> "数字"
                            "json" -> "结构化数据（JSON）"
                            else -> "文本值"
                        }) },
                        minLines = if (kind == "json") 2 else 1,
                        maxLines = if (kind == "json") 6 else 2,
                        placeholder = { Text(when (kind) {
                            "number" -> "例如 30"
                            "json" -> "例如 {\\\"enabled\\\":true}"
                            else -> "例如 on-request"
                        }) }
                    )
                }
                CodexPrimaryButton(
                    onClick = {
                        try {
                            require(path.isNotBlank()) { "请填写设置项名称" }
                            parsed = ConfigInputParser.parse(kind, value, booleanValue)
                            vm.errors.value = vm.errors.value - "config"
                            showConfirm = true
                        } catch (failure: Exception) {
                            vm.errors.value = vm.errors.value +
                                ("config" to (failure.message ?: "设置值格式不正确"))
                        }
                    },
                    enabled = path.isNotBlank() &&
                        (kind == "boolean" || value.isNotBlank()) &&
                        "config" !in loading,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("检查并保存")
                }
            }
        }
    }
    if (showConfirm) CodexConfirmDialog(
        title = "保存本机设置",
        message = "本次操作将更新 Codex 在当前设备中的运行配置。",
        detail = "设置项：$path\n设置值：" +
            if (kind == "boolean") (if (booleanValue) "启用" else "关闭") else value,
        confirmLabel = "确认保存",
        onConfirm = {
            vm.rpc("config", "config/value/write",
                JSONObject().put("keyPath", path)
                    .put("value", parsed).put("mergeStrategy", "replace"),
                after = {
                    vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
                })
            showConfirm = false
        },
        onDismiss = { showConfirm = false }
    )
}


@Composable
fun AppearancePage() {
    val compact by UiPreferences.compact.collectAsState()
    val lessMotion by UiPreferences.reducedMotion.collectAsState()
    val softMaterial by UiPreferences.softMaterial.collectAsState()
    ScreenColumn {
        SectionIntro("外观与交互", "全局典雅白底，暖铜文字与边框强调操作，支持轻阴影和精简动效。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("界面材料", style = MaterialTheme.typography.titleMedium)
            Text("典雅白底 / 柔和细边 / 清晰文字层级", color = Ink.muted)
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("紧凑信息密度")
                    Text("缩小面板间距，让代码和数据占据更多空间", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                WhiteSwitch(compact, onCheckedChange = UiPreferences::setCompact)
            }
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("柔雾层次材质")
                    Text("开启后增加轻微卡片阴影；关闭后保留白底和细边框",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                WhiteSwitch(softMaterial, onCheckedChange = UiPreferences::setSoftMaterial)
            }
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("减弱页面动效")
                    Text("缩短或关闭转场动画，保留状态反馈", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                WhiteSwitch(lessMotion, onCheckedChange = UiPreferences::setReducedMotion)
            }
        }
        CodexActionTile("交互预览", "点击可切换紧凑排版，查看触摸反馈", CodexGlyph.TouchApp,
            onClick = { UiPreferences.setCompact(!compact) }, modifier = Modifier.fillMaxWidth())
        CodexNotice(if (lessMotion) "已关闭装饰性位移动画，状态仍会立即更新"
            else "页面转场与操作反馈已经启用", NoticeTone.Info)
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("颜色职责", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill("主要操作", tone = StatusTone.Info)
                StatusPill("风险确认", tone = StatusTone.Danger)
            }
            Text("页面、卡片、选中态和按钮均使用白色背景；暖铜用于关键文字、图标和边框。", color = Ink.muted)
        }
    }
}

@Composable
fun AboutPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsState()
    val appContext = LocalContext.current
    val version = remember(appContext) {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }
    ScreenColumn {
        SectionIntro("关于 Codex Android", "官方运行核心 + 原生 Android 操作界面")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("v$version · Android 29+", style = MaterialTheme.typography.titleLarge)
            Text("上游官方项目：openai/codex", color = Ink.muted)
            Text("本项目仓库：sy220284/111", color = Ink.muted)
            Text("运行核心：Codex app-server（独立本地进程）", color = Ink.muted)
            StatusPill(if (status == EngineStatus.Connected) "内核可用" else "需要检查内核", status == EngineStatus.Connected)
            DividerLine()
            Text("隐私与文件", style = MaterialTheme.typography.titleMedium)
            Text("工程文件和 Codex 配置默认存储于 Android 应用私有目录。网络请求由 Codex 核心和用户启用的插件处理。账号密钥由官方运行核心管理。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            Text("源码来源的 Apache-2.0 许可及 NOTICE 保存在 upstream/codex/ 目录。", color = Ink.muted)
            Text("增量更新使用 HDiffPatch（MIT），许可文本随 APK 附带。", color = Ink.muted)
        }
    }
}
