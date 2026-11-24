package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import org.json.JSONObject
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

@Composable
fun SecurityPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val data by vm.data.collectAsState()
    var saving by remember { mutableStateOf(false) }
    val defaultSaveBusy by vm.securitySaveBusy.collectAsState()
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf(false) }
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val current = data["config"]?.optJSONObject("config")
    val requirements = data["securityRequirements"]?.optJSONObject("requirements")
    val profiles = records(data["permissionProfiles"])
    val defaultProfile = current?.optString("default_permissions").orEmpty().ifBlank {
        data["securityRequirements"]?.optJSONObject("requirements")?.optString("defaultPermissions").orEmpty()
    }
    val effectiveApproval = current?.optString("approval_policy").orEmpty()
    val effectiveSandbox = current?.optString("sandbox_mode").orEmpty()
    val activeThread by vm.activeThread.collectAsState()
    var scope by rememberSaveable { mutableStateOf("default") }
    var draftProfile by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val selected = draftProfile ?: defaultProfile
    val allowedProfiles = requirements?.optJSONObject("allowedPermissionProfiles")
    val canSelect: (JSONObject) -> Boolean = { profile ->
        val id = profile.optString("id")
        profile.optBoolean("allowed") &&
            (allowedProfiles == null || !allowedProfiles.has(id) || allowedProfiles.optBoolean(id)) &&
            (scope != "thread" || activeThread != null)
    }
    fun reload() {
        vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
        vm.refresh("securityRequirements", "configRequirements/read")
        vm.refresh("permissionProfiles", "permissionProfile/list",
            JSONObject().put("cwd", vm.workingDirectory))
    }
    LaunchedEffect(vm.workingDirectory) {
        if (SettingsDeepLink.take("security") == "thread" && activeThread != null) scope = "thread"
        reload()
    }
    Column(Modifier.fillMaxSize()) {
    ScreenColumn(modifier = Modifier.weight(1f)) {
        SectionIntro("权限与安全",
            "选择 Codex 可以执行的操作范围，调整前可查看当前设置。")
        ProtocolFeedback(vm, listOf("config", "securityRequirements", "permissionProfiles",
            "securitySave", "securityThreadSave"), onRetry = { reload() })

        saveMessage?.let { message ->
            if (saveError) ErrorText(message) else CodexNotice(message, NoticeTone.Info)
        }

        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Security, null, tint = Ink.jade,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("当前默认权限", color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (current != null) StatusPill("已读取", tone = StatusTone.Success)
            }
            Text(when {
                current == null -> "未读取"
                defaultProfile.isBlank() -> "沿用内核默认权限"
                else -> PresentationPolicy.permissionProfile(defaultProfile)
            }, style = MaterialTheme.typography.titleLarge, color = Ink.parchment)
            DividerLine()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text("审批方式", color = Ink.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp))
                Text(if (effectiveApproval.isBlank()) "未读取"
                    else PresentationPolicy.approvalMode(effectiveApproval),
                    color = Ink.parchment, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text("文件访问", color = Ink.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp))
                Text(if (effectiveSandbox.isBlank()) "未读取"
                    else PresentationPolicy.sandboxMode(effectiveSandbox),
                    color = Ink.parchment, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
            }
        }

        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("调整范围", style = MaterialTheme.typography.titleMedium)
            Text("新任务默认设置与当前会话分别保存，互不覆盖。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecurityScopeOption(
                    label = "新任务默认",
                    subtitle = "以后创建的任务",
                    selected = scope == "default", enabled = true,
                    modifier = Modifier.weight(1f).testTag("security-scope-default")
                ) {
                    scope = "default"
                    draftProfile = null
                }
                SecurityScopeOption(
                    label = "当前会话",
                    subtitle = if (activeThread == null) "暂无可用会话" else "此会话后续操作",
                    selected = scope == "thread", enabled = activeThread != null,
                    modifier = Modifier.weight(1f).testTag("security-scope-thread")
                ) {
                    scope = "thread"
                    draftProfile = null
                }
            }
        }

        MaterialPanel(Modifier.fillMaxWidth()) {
            Text(
                "选择权限档位",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("security-profiles-heading")
            )
                Column(Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("可用权限方案",
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    if (profiles.isEmpty()) {
                        when {
                            "permissionProfiles" in loading -> CodexSkeletonRows(2)
                            errors["permissionProfiles"] != null -> Unit // 上方统一反馈提供重试。
                            data["permissionProfiles"] != null -> CodexEmptyState(
                                "暂无可选权限方案", "内核当前未返回权限方案。")
                            else -> Text("尚未读取权限方案，请刷新。",
                                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    profiles.forEach { profile ->
                        val id = profile.optString("id")
                        val allowed = canSelect(profile)
                        val isSelected = if (scope == "default") selected == id
                            else draftProfile == id
                        SecurityProfileOption(
                            name = PresentationPolicy.permissionProfile(id),
                            description = when (id) {
                                ":read-only" -> "可查看工作区文件；修改时需批准"
                                ":workspace" -> "允许编辑项目文件；越界操作仍需批准"
                                ":danger-full-access" -> "允许访问工作区外的文件及网络"
                                else -> profile.optString("description").takeUnless {
                                    it.isBlank() || it == "null"
                                }.orEmpty()
                            },
                            allowed = allowed,
                            selected = isSelected,
                            isDangerous = id == ":danger-full-access",
                            modifier = Modifier.testTag(
                                "security-profile-" + id.removePrefix(":"))
                        ) { draftProfile = id }
                    }
                    if (draftProfile != null) {
                        MaterialPanel(Modifier.fillMaxWidth()) {
                            Text("待保存", color = Ink.muted,
                                style = MaterialTheme.typography.labelSmall)
                            Text(PresentationPolicy.permissionProfile(draftProfile.orEmpty()),
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink.parchment)
                            CodexTextAction(onClick = { draftProfile = null }) {
                                Text("撤销修改")
                            }
                        }
                    }
                    if (draftProfile == ":danger-full-access") {
                        CodexNotice("完全访问允许操作项目以外的文件及网络。保存时需要再次确认。",
                            NoticeTone.Warning)
                    }
                    Text(if (scope == "thread")
                        "仅影响当前会话之后的操作。"
                        else "保存后，新创建的任务将使用该权限方案。",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                }
        }
        if (requirements != null) {
            Text("实际权限仍受运行环境和管理员安全策略约束。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (draftProfile != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Ink.elevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, Ink.border)
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("待保存 · " + PresentationPolicy.permissionProfile(draftProfile.orEmpty()),
                    color = Ink.parchment, style = MaterialTheme.typography.bodyMedium)
                CodexPrimaryButton(
                    onClick = { confirm = true },
                    enabled = (scope == "thread" || draftProfile != defaultProfile) &&
                        !saving && !defaultSaveBusy && "securityThreadSave" !in loading,
                    busy = saving || defaultSaveBusy || "securityThreadSave" in loading,
                    modifier = Modifier.fillMaxWidth().testTag("security-save")
                ) {
                    Text(if (scope == "thread") "应用到当前会话" else "保存默认权限")
                }
            }
        }
    }
    }
    if (confirm && draftProfile != null) {
        val id = draftProfile!!
        CodexConfirmDialog(
            title = if (id == ":danger-full-access") "确认启用完全访问" else "确认权限调整",
            message = if (id == ":danger-full-access")
                "此方案允许访问工作区以外的文件和网络，执行风险较高。"
                else "权限调整将影响选定范围内的后续操作。",
            detail = "目标：" + (if (scope == "thread") "当前会话" else "新任务默认权限") +
                "\n方案：" + PresentationPolicy.permissionProfile(id),
            confirmLabel = "确认保存",
            destructive = id == ":danger-full-access",
            onConfirm = {
                if (scope == "thread") {
                    activeThread?.let { threadId ->
                        vm.rpc("securityThreadSave", "thread/settings/update",
                            JSONObject().put("threadId", threadId).put("permissions", id),
                            after = { vm.refresh("securityThreadRead", "thread/read",
                                JSONObject().put("threadId", threadId)) })
                    }
                } else {
                    val snapshot = vm.globalWorkSnapshot()
                    if (defaultSaveBusy) {
                        saveMessage = "已有默认权限修改正在保存与回读，请完成后重试"
                        saveError = true
                    } else if (!snapshot.canStartNewTask) {
                        saveMessage = snapshot.blockReason
                        saveError = true
                    } else {
                        // Own the entire write + readback even if this page is closed.
                        vm.securitySaveBusy.value = true
                        saving = true
                        vm.viewModelScope.launch {
                        saveMessage = null
                        try {
                            val verified = SecurityPermissionController.saveDefault(vm.bridge, id)
                            vm.data.value = vm.data.value + ("config" to verified)
                            saveMessage = "默认权限已保存并核实生效"
                            saveError = false
                        } catch (failure: Exception) {
                            saveMessage = failure.message ?: "权限保存或回读失败"
                            saveError = true
                            reload()
                        } finally {
                            vm.securitySaveBusy.value = false
                            saving = false
                        }
                        }
                    }
                }
                draftProfile = null
                confirm = false
            },
            onDismiss = { confirm = false }
        )
    }
}



/** Two mutually exclusive application scopes, with a readable disabled state. */
@Composable
private fun SecurityScopeOption(
    label: String, subtitle: String, selected: Boolean,
    enabled: Boolean, modifier: Modifier = Modifier, onChoose: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .border(BorderStroke(if (selected) 1.3.dp else 1.dp,
                if (selected) Ink.coral else if (enabled) Ink.controlBorder else Ink.border), shape)
            .background(
                if (selected) Ink.coral.copy(alpha = .055f) else Ink.panel, shape)
            .codexSelectable(selected, enabled, onChoose)
            .heightIn(min = 64.dp)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodexRadio(selected = selected, onClick = null, enabled = enabled,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(4.dp))
            Text(label,
                color = if (enabled) Ink.parchment else Ink.muted,
                style = MaterialTheme.typography.titleSmall)
        }
        Text(subtitle, color = Ink.muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Shared row state for built-in and custom permission profiles. */
@Composable
private fun SecurityProfileOption(
    name: String, description: String, allowed: Boolean,
    selected: Boolean, isDangerous: Boolean,
    modifier: Modifier = Modifier, onChoose: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier.fillMaxWidth()
            .border(BorderStroke(if (selected) 1.4.dp else .8.dp,
                if (selected) Ink.coral else if (allowed) Ink.controlBorder else Ink.border), shape)
            .background(if (selected) Ink.coral.copy(alpha = .055f)
                else Ink.panel, shape)
            .codexSelectable(selected, allowed, onChoose)
            .heightIn(min = 72.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CodexRadio(selected = selected, onClick = null, enabled = allowed,
            modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall,
                color = if (allowed) Ink.parchment else Ink.muted)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = Ink.muted)
            }
            if (isDangerous) {
                Text("高风险权限", style = MaterialTheme.typography.labelMedium,
                    color = Ink.red)
            }
        }
        if (!allowed) StatusPill("受限", good = false)
        else if (selected) Text("已选", style = MaterialTheme.typography.labelMedium,
            color = Ink.coral)
    }
}
