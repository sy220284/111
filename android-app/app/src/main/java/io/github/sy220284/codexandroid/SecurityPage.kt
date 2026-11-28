package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Only three permission choices, in the composer; saving survives closing the popup. */
@Composable
internal fun ComposerPermissionControl(vm: CodexViewModel, enabled: Boolean,
    open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val data by vm.data.collectEntries("composerPermissionWorkspace", "composerPermissionConfig", "permissionProfiles", "securityRequirements")
    val loading by vm.loading.collectProjection { it.intersect(setOf("config", ENGINE_SETTING_SAVE, "appMutation", "skillsWrite", "configWrite", "modelProviderWrite", "composerPermissionConfig", "securityRequirements", "permissionProfiles")) }
    val errors by vm.errors.collectEntries("securitySave")
    val saving by vm.securitySaveBusy.collectAsStateWithLifecycle()
    val status by vm.bridge.status.collectAsStateWithLifecycle()
    val workspace by vm.activeWorkspace.collectAsStateWithLifecycle()
    val remote by vm.conversationState.collectProjection { it.remote }
    var confirmFull by remember(workspace) { mutableStateOf(false) }
    val currentWorkspace = data["composerPermissionWorkspace"]?.optString("cwd") == workspace
    val isolated = AnswerWorkspaceStore.isolated(java.io.File(vm.workingDirectory), workspace)
    val selected = if (currentWorkspace) AnswerWorkspaceStore.permission(java.io.File(vm.workingDirectory), workspace,
        ComposerPermissionPolicy.selected(data["composerPermissionConfig"])) else null
    val profileData = data["permissionProfiles"]
    val profiles = remember(profileData) { records(profileData) }
    val reading = !currentWorkspace || loading.any { it in setOf("composerPermissionConfig", "securityRequirements", "permissionProfiles") }
    val blocked = vm.composerPermissionBlockReason()
    LaunchedEffect(status, workspace) {
        if (status == EngineStatus.Connected) vm.refreshComposerPermissions()
    }
    Box(Modifier.fillMaxWidth()) {
        CodexTextAction(onClick = onOpen,
            modifier = Modifier.fillMaxWidth().testTag("composer-permission-control"),
            contentPadding = PaddingValues(horizontal = 2.dp)) {
            CodexIcon(CodexGlyph.Security, null, Modifier.size(16.dp),
                tint = if (selected == ":danger-full-access") Ink.red else Ink.muted)
            Spacer(Modifier.width(5.dp))
            Text(when {
                saving -> "权限保存中"
                status != EngineStatus.Connected -> "权限"
                reading -> "读取权限中"
                selected != null -> PresentationPolicy.permissionProfile(selected)
                else -> "选择权限"
            }, modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(14.dp))
        }
        if (open) CodexFloatingMenu(title = "执行权限", onDismiss = onDismiss, wide = true,
            description = if (isolated) "回答版本使用独立工作目录，仅可选择只读或工作区权限。" else "选择后自动保存，后续发送的任务采用此权限。") {
            if (remote == null) Text("此处选择 Codex 执行权限；本机系统级文件和网络隔离尚未验证。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            Text("可用权限方案", modifier = Modifier.testTag("security-profiles-heading"),
                style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            ProtocolFeedback(vm, listOf("composerPermissionConfig", "securityRequirements", "permissionProfiles"),
                onRetry = { vm.refreshComposerPermissions() })
            if (saving) AtlasOperationProgress("正在保存并核实权限")
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                ComposerPermissionPolicy.ids.forEach { id ->
                    val allowed = ComposerPermissionPolicy.allowed(id, profiles, data["securityRequirements"]) && !(isolated && id == ":danger-full-access")
                    val selectable = enabled && !saving && !reading && allowed && blocked == null
                    CodexFloatingMenuRow(
                        title = PresentationPolicy.permissionProfile(id),
                        description = when (id) {
                            ":read-only" -> "查看文件，修改需要批准"
                            ":workspace" -> "编辑工作区文件，越界需要批准"
                            else -> "允许访问工作区之外的文件与网络"
                        } + if (!allowed && !reading && profiles.isNotEmpty()) " · 受限" else "",
                        modifier = Modifier.testTag("security-profile-" + id.removePrefix(":")),
                        selected = selected == id, enabled = selectable,
                        destructive = id == ":danger-full-access",
                        onClick = {
                            if (selected != id && vm.activeWorkspace.value == workspace) {
                                onDismiss()
                                if (id == ":danger-full-access") confirmFull = true
                                else vm.chooseComposerPermission(id)
                            }
                        }
                    )
                }
            }
            if (!reading && blocked != null) Text(blocked, color = Ink.muted)
            else if (!enabled) Text("当前任务结束后可调整权限", color = Ink.muted)
        }
    }
    errors["securitySave"]?.let { CodexNotice(it, NoticeTone.Error) }
    if (confirmFull) CodexConfirmDialog(
        title = "确认启用完全访问",
        message = "后续发送的任务可以访问工作区以外的文件和网络，执行风险较高。",
        confirmLabel = "确认启用", destructive = true,
        onConfirm = { confirmFull = false; vm.chooseComposerPermission(":danger-full-access") },
        onDismiss = { confirmFull = false }
    )
}
