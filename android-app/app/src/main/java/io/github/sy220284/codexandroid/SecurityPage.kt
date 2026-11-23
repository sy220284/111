package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun SecurityPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
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
    var showProfiles by rememberSaveable { mutableStateOf(false) }
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
        if (SettingsDeepLink.take("security") == "profiles") showProfiles = true
        reload()
    }
    ScreenColumn {
        SectionIntro("TRUST / EXECUTION", "运行权限与安全",
            "从 Codex 内核选择实际允许的权限方案，并确认作用范围。")
        ProtocolFeedback(vm, listOf("config", "securityRequirements", "permissionProfiles",
            "securitySave", "securityThreadSave"), onRetry = { reload() })
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("当前默认权限", style = MaterialTheme.typography.titleMedium)
            Text("权限方案：" + PresentationPolicy.permissionProfile(defaultProfile),
                color = Ink.muted)
            Text("审批：" + PresentationPolicy.approvalMode(effectiveApproval), color = Ink.muted)
            Text("文件访问：" + PresentationPolicy.sandboxMode(effectiveSandbox), color = Ink.muted)
            DividerLine()
            Text("修改作用范围", style = MaterialTheme.typography.titleMedium)
            listOf("default" to "后续新任务默认权限",
                "thread" to "当前会话后续操作").forEach { (value, title) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = value != "thread" || activeThread != null) {
                        scope = value
                        draftProfile = null
                    }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = scope == value, onClick = {
                        scope = value
                        draftProfile = null
                    }, enabled = value != "thread" || activeThread != null)
                    Text(title, modifier = Modifier.weight(1f))
                }
            }
            if (activeThread == null)
                Text("当前没有已建立的会话，可先设置新任务默认权限。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showProfiles = !showProfiles },
            modifier = Modifier.testTag("security-profiles-toggle")) {
            Icon(if (showProfiles) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Spacer(Modifier.width(6.dp))
            Text(if (showProfiles) "收起权限方案" else "查看可用权限方案")
        }
        CodexReveal(showProfiles) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("可用权限方案", style = MaterialTheme.typography.titleMedium)
                Text("仅允许选择当前工作目录与管理策略许可的方案。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                if (profiles.isEmpty()) Text("正在读取权限方案或暂无可用方案。",
                    color = Ink.muted)
                profiles.forEach { profile ->
                    val id = profile.optString("id")
                    val allowed = canSelect(profile)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(enabled = allowed) { draftProfile = id }
                        .testTag("security-profile-" + id.removePrefix(":")),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = if (scope == "default") selected == id else draftProfile == id,
                            onClick = { draftProfile = id }, enabled = allowed)
                        Column(Modifier.weight(1f)) {
                            Text(PresentationPolicy.permissionProfile(id),
                                style = MaterialTheme.typography.titleSmall)
                            val description = profile.optString("description")
                            if (description.isNotBlank() && description != "null")
                                Text(description, color = Ink.muted,
                                    style = MaterialTheme.typography.bodySmall)
                        }
                        StatusPill(if (allowed) "可用" else "受策略限制", allowed)
                    }
                }
                if (draftProfile != null) {
                    TextButton(onClick = { draftProfile = null }) { Text("放弃本次修改") }
                }
                CodexPrimaryButton(
                    onClick = { confirm = true },
                    enabled = draftProfile != null &&
                        (scope == "thread" || draftProfile != defaultProfile) &&
                        "securitySave" !in loading && "securityThreadSave" !in loading,
                    busy = "securitySave" in loading || "securityThreadSave" in loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (scope == "thread") "应用到当前会话" else "保存默认权限") }
            }
        }
        if (requirements != null) CodexNotice(
            "权限受当前运行环境和管理策略约束，实际生效范围以保存后的内核状态为准。",
            NoticeTone.Info)
    }
    if (confirm && draftProfile != null) {
        val id = draftProfile!!
        CodexConfirmDialog(
            title = if (id == ":danger-full-access") "确认启用完全访问" else "确认权限调整",
            message = if (id == ":danger-full-access")
                "此方案允许访问工作区以外的文件和网络，执行风险较高。"
                else "权限调整将影响选定范围内的后续操作。",
            detail = "目标：" + (if (scope == "thread") "当前会话" else "新任务默认权限") +
                "\\n方案：" + PresentationPolicy.permissionProfile(id),
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
                    vm.saveDefaultPermissionProfile(id)
                }
                draftProfile = null
                confirm = false
            },
            onDismiss = { confirm = false }
        )
    }
}

