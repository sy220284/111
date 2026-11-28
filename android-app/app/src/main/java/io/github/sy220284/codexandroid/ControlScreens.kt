package io.github.sy220284.codexandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val prompts by vm.bridge.prompts.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val auditRecords by vm.approvalAudit.records.collectAsStateWithLifecycle()
    val auditError by vm.approvalAudit.error.collectAsStateWithLifecycle()
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
fun ApprovalQuickSheet(vm: CodexViewModel, threadId: String? = null, onDismiss: () -> Unit) {
    val prompts by vm.bridge.prompts.collectProjection { all ->
        if (threadId == null) all else all.forThread(threadId)
    }
    ApprovalQuickSheetContent(vm, prompts, onDismiss, includeAudit = threadId == null)
}

internal fun List<ServerPrompt>.forThread(threadId: String?): List<ServerPrompt> =
    if (threadId.isNullOrBlank()) emptyList() else filter { it.params.optString("threadId") == threadId }

/** Complete sheet bound to an explicit prompt snapshot for deterministic rendering. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalQuickSheetContent(
    vm: CodexViewModel, prompts: List<ServerPrompt>, onDismiss: () -> Unit, includeAudit: Boolean = true
) {
    val auditRecords by vm.approvalAudit.records.collectAsStateWithLifecycle()
    val auditError by vm.approvalAudit.error.collectAsStateWithLifecycle()
    CodexDetailSheet(title = "待处理审批", onDismiss = onDismiss, startExpanded = true,
        subtitle = "请根据具体命令、文件和权限范围作出决定。") {
        if (prompts.isEmpty()) {
            CodexEmptyState("当前审批已处理", "返回任务即可继续查看执行进展。",
                icon = CodexGlyph.Security)
            CodexSecondaryButton(onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("返回当前任务")
            }
        } else {
            AtlasListHeading("待处理操作", prompts.size)
            prompts.forEachIndexed { index, prompt ->
                StatusPill("第 ${index + 1}/${prompts.size} 项", false, StatusTone.Warning)
                key(prompt.id.toString()) { ApprovalRequestCard(vm, prompt) }
            }
        }
        if (includeAudit && (auditRecords.isNotEmpty() || !auditError.isNullOrBlank())) {
            ApprovalAuditSection(auditRecords, auditError) { vm.approvalAudit.clear() }
        }
        Spacer(Modifier.height(12.dp))
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
    var questionIndex by remember(prompt.id.toString()) { mutableIntStateOf(0) }
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
        if (normal || permission) {
            WorkSection(Modifier.fillMaxWidth(), WorkSectionKind.Status) {
                WorkSectionHeading("确认影响范围", when (method) {
                    "item/commandExecution/requestApproval" -> "允许后，核心将执行此命令。请核对命令与执行目录。"
                    "item/fileChange/requestApproval" -> "允许后，核心可执行本次请求的文件变更。"
                    else -> "请核对下面实际申请的权限及授权期限。"
                })
            }
        }
        if (command.isNotBlank()) {
            Text("待执行命令", color = Ink.copper, style = MaterialTheme.typography.labelMedium)
            WorkSection(Modifier.fillMaxWidth(), WorkSectionKind.Reading) {
                Text(
                    if (commandExpanded || command.length <= 240) command else command.take(240) + "…",
                    fontFamily = FontFamily.Monospace, color = Ink.parchment
                )
            }
            if (command.length > 240) CodexTextAction(
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
            val index = questionIndex.coerceIn(0, (items.length() - 1).coerceAtLeast(0))
            val unanswered = (0 until items.length()).filter { n ->
                answers[items.optJSONObject(n)?.optString("id").orEmpty()]?.isNotBlank() != true
            }
            Text("问题 ${index + 1} / ${items.length()} · 已回答 ${items.length() - unanswered.size} 项 · 未答 ${unanswered.size} 项", color = Ink.muted)
            if (unanswered.isNotEmpty() && index !in unanswered) {
                CodexTextAction(onClick = {
                    questionIndex = unanswered.firstOrNull { it > index } ?: unanswered.first()
                }, modifier = Modifier.testTag("approval-next-unanswered")) { Text("定位未回答问题") }
            }
            items.optJSONObject(index)?.let { q ->
                val id = q.optString("id")
                val options = q.optJSONArray("options")
                Text(q.optString("header").ifBlank { "问题 " + (index + 1) },
                    color = Ink.copper, style = MaterialTheme.typography.labelMedium)
                Text(q.optString("question"), style = MaterialTheme.typography.bodyMedium)
                if (options != null) {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (opt in 0 until options.length()) {
                            val choice = options.optJSONObject(opt) ?: continue
                            val label = choice.optString("label")
                            AtlasChoiceRow(label, choice.optString("description"),
                                selected = answers[id] == label,
                                onClick = {
                                    answers[id] = label
                                    if (index + 1 < items.length()) questionIndex = index + 1
                                },
                                modifier = Modifier.testTag("approval-answer-" + id + "-" + opt))
                        }
                    }
                }
                ActionReplyRow(
                    value = answers[id] ?: "", onValueChange = { answers[id] = it },
                    label = { Text(if (options == null) "输入回答" else "或者输入其他回答") },
                    visualTransformation = if (q.optBoolean("isSecret"))
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(), singleLine = !q.optBoolean("isOther"),
                    directText = options == null
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CodexTextAction(onClick = { questionIndex-- }, enabled = index > 0) { Text("上一个问题") }
                CodexTextAction(onClick = { questionIndex++ }, enabled = index + 1 < items.length()) { Text("下一个问题") }
            }
        }
        val questions = prompt.params.optJSONArray("questions") ?: org.json.JSONArray()
        val canSubmit = questions.length() > 0 && (0 until questions.length()).all {
            val id = questions.optJSONObject(it)?.optString("id") ?: ""
            answers[id]?.isNotBlank() == true
        }
        AtlasApprovalActions(
            primary = when {
                normal -> { modifier ->
                    CodexPrimaryButton(
                        onClick = { vm.answerApproval(prompt, ApprovalAction.AllowOnce) },
                        modifier = modifier.testTag("approval-allow-once")
                    ) { Text("仅本次允许") }
                }
                permission -> { modifier ->
                    CodexPrimaryButton(onClick = { grantPermissions = true },
                        modifier = modifier.testTag("approval-grant-turn")) { Text("授权本轮") }
                }
                question -> { modifier ->
                    CodexPrimaryButton(onClick = { vm.answerUserInput(prompt, answers.toMap()) },
                        enabled = canSubmit, modifier = modifier.testTag("approval-submit-answers")
                            .explainDisabled(!canSubmit, "需要先回答全部问题才能提交")
                    ) { Text("提交全部回答") }
                }
                else -> null
            },
            secondary = if (!question) { modifier ->
                CodexSecondaryButton(onClick = { vm.answerApproval(prompt, ApprovalAction.Decline) },
                    modifier = modifier.testTag("approval-decline")) { Text("拒绝") }
            } else null,
            tertiary = if (normal && kind != "humanCommand") { modifier ->
                CodexTextAction(onClick = { morePermissions = true },
                    modifier = modifier.heightIn(min = 48.dp).testTag("approval-more")) {
                    CodexIcon(CodexGlyph.MoreHoriz, null)
                    Spacer(Modifier.width(6.dp))
                    Text("更多授权")
                }
            } else null
        )
        if (question) Text("等待回答时可返回对话查看任务；停止任务可取消本次提问。",
            color = Ink.muted, style = MaterialTheme.typography.labelSmall)
    }
    if (morePermissions) CodexActionDialog(
        title = "更多授权选项",
        onDismiss = { morePermissions = false },
        actions = listOf(CodexSheetAction("在本会话持续允许",
            CodexGlyph.TaskApproval, onClick = { grantSession = true },
            description = "需要再次确认授权范围"))
    )
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
    val engineStatus by vm.bridge.status.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("config") == "advanced") advanced = true
    }
    val config = data["config"]?.optJSONObject("config")
    val ready = config != null
    val saving = ENGINE_SETTING_SAVE in loading
    val receipt = data[ENGINE_SETTING_SAVE]?.takeIf { it.optString("owner") == "config" }
    val saveError = if (receipt != null) errors[ENGINE_SETTING_SAVE] else null
    val saveMessage = receipt?.optString("message")?.takeIf { it.isNotBlank() }
    fun clearSaveFeedback() {
        if (!saving && vm.data.value[ENGINE_SETTING_SAVE]?.optString("owner") == "config") {
            vm.errors.value = vm.errors.value - ENGINE_SETTING_SAVE
            vm.data.value = vm.data.value - ENGINE_SETTING_SAVE
        }
    }
    val permissionBusy by vm.securitySaveBusy.collectAsStateWithLifecycle()
    val enabled = ready && engineStatus == EngineStatus.Connected && "config" !in loading && !saving && !permissionBusy
    LaunchedEffect(engineStatus) {
        if (engineStatus == EngineStatus.Connected) {
            if ("config" !in vm.loading.value && !saving) vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
        }
    }

    ScreenColumn {
        SectionIntro("开发者设置",
            "专业参数编辑与本机运行诊断。日常模型、工作偏好、上下文和工具设置各有独立入口。")
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
                CodexIconAction(onClick = {
                    vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
                }, enabled = "config" !in loading && !saving) {
                    CodexIcon(CodexGlyph.Retry, "读取生效配置")
                }
            }
            if ("config" in loading) AtlasOperationProgress("正在读取生效配置")
            Text("配置保存在当前设备，修改成功后影响后续任务。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (ready && "config" !in loading) errors["config"]?.let { ErrorText(it) }
        if (!ready && "config" !in loading) {
            if (engineStatus != EngineStatus.Connected) {
                CodexRecoveryState("连接后可读取引擎配置",
                    "运行核心连接后可查看生效配置并编辑高级参数。",
                    "重新连接", { vm.connect() }, { navigate("diagnostics") },
                    artworkRes = R.drawable.ic_empty_connection)
            } else {
                CodexRecoveryState(
                    if (errors["config"] != null) "配置读取失败" else "配置尚未读取",
                    "可重新请求本机生效配置，读取后再修改。",
                    "重新读取", {
                        vm.refresh("config", "config/read",
                            JSONObject().put("includeLayers", true))
                    }, { navigate("diagnostics") },
                    artworkRes = if (errors["config"] != null) R.drawable.ic_empty_request_failed
                        else R.drawable.ic_empty_content)
            }
        }
        if (saving) AtlasOperationProgress("正在保存并核实配置")
        saveError?.let { ErrorText(it) }
        saveMessage?.let {
            CodexNotice(it, NoticeTone.Success)
        }
        WorkSection(Modifier.fillMaxWidth()) {
            CodexDetailTrigger("诊断与日志", { navigate("diagnostics") }, Modifier.fillMaxWidth())
        }
        Text("专业配置", style = MaterialTheme.typography.titleSmall, color = Ink.muted)
        HookPolicySettings(vm, data["config"], enabled)
        CodexDetailTrigger(
            label = "高级自定义配置",
            onClick = { clearSaveFeedback(); advanced = true },
            modifier = Modifier.fillMaxWidth().testTag("config-advanced-toggle")
        )
        if (advanced) CodexDetailSheet(
            title = "高级自定义配置", startExpanded = true,
            subtitle = "描述需要实现的效果，Codex 核对真实配置后处理。",
            onDismiss = { advanced = false }
        ) {
            AgentActionRow(vm, "交给 Codex 配置", "保留现有设置与确认流程",
                "请帮我调整本机 Codex 的高级配置。先了解我要实现的效果，核对当前生效配置和支持的参数；说明变更范围，待我确认后再修改，并重新读取核实。不要请求或显示密钥，不改变现有审批和安全策略。",
                enabled = !saving && !permissionBusy, modifier = Modifier.testTag("config-agent-action"))
        }
    }
}


@Composable
fun AppearancePage(vm: CodexViewModel) {
    val target = remember { SettingsDeepLink.take("appearance") }
    ScreenColumn {
        AppearanceInlineControls(focusTarget = target, modifier = Modifier)
        EngineSettingsSection(vm, "appearance", target)
    }
}

@Composable
fun AboutPage(vm: CodexViewModel) {
    val status by vm.bridge.status.collectAsStateWithLifecycle()
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
