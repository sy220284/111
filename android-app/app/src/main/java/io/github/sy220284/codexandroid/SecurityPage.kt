package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Only three permission choices, in the composer; saving survives closing the sheet. */
@Composable
internal fun ComposerPermissionControl(vm: CodexViewModel, enabled: Boolean) {
    val data by vm.data.collectEntries("composerPermissionWorkspace", "composerPermissionConfig", "permissionProfiles", "securityRequirements")
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectEntries("securitySave")
    val saving by vm.securitySaveBusy.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val workspace by vm.activeWorkspace.collectAsState()
    var open by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val currentWorkspace = data["composerPermissionWorkspace"]?.optString("cwd") == workspace
    val selected = if (currentWorkspace) ComposerPermissionPolicy.selected(data["composerPermissionConfig"]) else null
    val profileData = data["permissionProfiles"]
    val profiles = remember(profileData) { records(profileData) }
    val reading = !currentWorkspace || loading.any { it in setOf("composerPermissionConfig", "securityRequirements", "permissionProfiles") }
    val blocked = vm.composerPermissionBlockReason()
    LaunchedEffect(status, workspace) {
        if (status == EngineStatus.Connected) vm.refreshComposerPermissions()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CodexTextAction(onClick = { open = true },
            modifier = Modifier.testTag("composer-permission-control"),
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
            }, style = MaterialTheme.typography.labelMedium)
            CodexIcon(CodexGlyph.ExpandMore, null, Modifier.size(14.dp))
        }
    }
    errors["securitySave"]?.let { CodexNotice(it, NoticeTone.Error) }
    if (open) CodexDetailSheet(title = "执行权限", onDismiss = { open = false },
        subtitle = "选择后自动保存，后续发送的任务采用此权限。", startExpanded = false) {
        Text("可用权限方案", modifier = Modifier.testTag("security-profiles-heading"),
            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        ProtocolFeedback(vm, listOf("composerPermissionConfig", "securityRequirements", "permissionProfiles"),
            onRetry = { vm.refreshComposerPermissions() })
        if (saving) AtlasOperationProgress("正在保存并核实权限")
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            ComposerPermissionPolicy.ids.forEach { id ->
                val allowed = ComposerPermissionPolicy.allowed(id, profiles, data["securityRequirements"])
                val selectable = enabled && !saving && !reading && allowed && blocked == null
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("security-profile-" + id.removePrefix(":"))
                    .codexSelectable(selected == id, selectable) {
                        if (selected != id) {
                            open = false
                            if (id == ":danger-full-access") confirmFull = true
                            else vm.chooseComposerPermission(id)
                        }
                    }.padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CodexRadio(selected = selected == id, onClick = null, enabled = selectable,
                        modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(PresentationPolicy.permissionProfile(id),
                            color = if (id == ":danger-full-access") Ink.red else Ink.parchment,
                            style = MaterialTheme.typography.titleSmall)
                        Text(when (id) {
                            ":read-only" -> "查看文件，修改需要批准"
                            ":workspace" -> "编辑工作区文件，越界需要批准"
                            else -> "允许访问工作区之外的文件与网络"
                        }, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!allowed && !reading && profiles.isNotEmpty()) StatusPill("受限", good = false)
                }
            }
        }
        if (!reading && blocked != null) Text(blocked, color = Ink.muted)
        else if (!enabled) Text("当前任务结束后可调整权限", color = Ink.muted)
    }
    if (confirmFull) CodexConfirmDialog(
        title = "确认启用完全访问",
        message = "后续发送的任务可以访问工作区以外的文件和网络，执行风险较高。",
        confirmLabel = "确认启用", destructive = true,
        onConfirm = { confirmFull = false; vm.chooseComposerPermission(":danger-full-access") },
        onDismiss = { confirmFull = false }
    )
}
