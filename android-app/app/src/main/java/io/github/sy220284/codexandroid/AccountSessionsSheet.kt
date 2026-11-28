package io.github.sy220284.codexandroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

/**
 * 仅在主动打开时读取实验性 accountSession/list。
 * 切换使用实际 sessionId/accountId，需用户确认，SSO 只通过显式按钮打开外部浏览器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSessionsSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val engine by vm.bridge.status.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loginBusy by vm.loginBusy.collectAsStateWithLifecycle()
    val loginId by vm.activeLoginId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val payload = data["accountSessions"]
    val currentSession = payload?.optString("activeSessionId").orEmpty()
    val entries = remember(payload) {
        val array = payload?.optJSONArray("sessions") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    val authUrl = data["accountSessionSwitch"]?.optString("authUrl").orEmpty()
    var target by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    LaunchedEffect(engine) {
        if (engine == EngineStatus.Connected) {
            vm.refresh("accountSessions", "accountSession/list",
                CatalogRequestContract.accountSessions())
        }
    }

    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        fullScreen = true,
        containerColor = Ink.canvas, contentColor = Ink.parchment) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()
            .navigationBarsPadding().padding(horizontal = 19.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            AtlasSheetHeader("账号与工作区切换", onDismiss)
            Text("查看可用账号与工作区；切换前会请你确认。",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            CodexSecondaryButton(
                onClick = {
                    if (engine == EngineStatus.Connected)
                        vm.refresh("accountSessions", "accountSession/list",
                            CatalogRequestContract.accountSessions())
                    else vm.connect()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = "accountSessions" !in loading
            ) {
                CodexIcon(CodexGlyph.Retry, null)
                Spacer(Modifier.width(8.dp))
                Text(if (engine == EngineStatus.Connected) "刷新账号与工作区" else "连接本机内核")
            }
            if ("accountSessions" in loading) AtlasOperationProgress("正在读取账号与工作区")
            if ("accountSessionSwitch" in loading) AtlasOperationProgress("正在切换账号工作区")
            errors["accountSessions"]?.let {
                CodexNotice("当前内核未返回账号会话列表：$it", NoticeTone.Warning)
            }
            errors["accountSessionSwitch"]?.let { ErrorText(it) }
            if (authUrl.startsWith("https://")) {
                CodexNotice("当前工作区需要浏览器身份验证。",
                    NoticeTone.Info, actionLabel = "打开授权页面", onAction = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                        } catch (failure: ActivityNotFoundException) {
                            vm.errors.value = vm.errors.value + ("accountSessionSwitch" to "未找到可用浏览器")
                        }
                    })
            }
            if (engine != EngineStatus.Connected)
                CodexNotice("连接内核后即可读取可切换的账号与工作区。", NoticeTone.Info)
            LazyColumn(Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 25.dp)) {
                if (entries.isEmpty() && payload != null && "accountSessions" !in loading && errors["accountSessions"] == null) {
                    item {
                        CodexEmptyState("暂无可切换的账号", "该能力需要当前内核支持多个账号会话。")
                    }
                }
                if (entries.isNotEmpty()) item { AtlasListHeading("可切换账号", entries.size, suffix = "个") }
                items(entries.sortedByDescending { it.optString("sessionId") == currentSession || it.optBoolean("isActive") },
                    key = { it.optString("sessionId") }) { session ->
                    val sid = session.optString("sessionId")
                    val active = sid == currentSession || session.optBoolean("isActive")
                    val workspaces = session.optJSONArray("workspaces") ?: JSONArray()
                    WorkSection(Modifier.fillMaxWidth(), if (active) WorkSectionKind.Status else WorkSectionKind.Group) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodexIcon(CodexGlyph.AccountCircle, null, tint = Ink.copper)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.optString("displayName").ifBlank {
                                    session.optString("email").ifBlank { "未命名账号" }
                                }, style = MaterialTheme.typography.titleMedium)
                                session.optString("email").takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Ink.muted,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (active) StatusPill("当前账号", true)
                        }
                        for (index in 0 until workspaces.length()) {
                            val workspace = workspaces.optJSONObject(index) ?: continue
                            val aid = workspace.optString("accountId")
                            if (aid.isBlank()) continue
                            val chosen = active &&
                                aid == session.optString("selectedWorkspaceAccountId")
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(PresentationPolicy.meaningful(workspace.optString("name"))
                                        .ifEmpty { "未命名工作区" },
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(when (workspace.optString("kind").lowercase()) {
                                        "personal" -> "个人工作区"
                                        "team", "organization" -> "团队工作区"
                                        else -> "工作区"
                                    },
                                        color = Ink.muted,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                if (chosen) Text("当前使用", color = Ink.jade)
                                else CodexSecondaryButton(
                                    onClick = {
                                        target = Triple(sid, aid,
                                            PresentationPolicy.meaningful(workspace.optString("name"))
                                                .ifEmpty { "未命名工作区" })
                                    }, enabled = !loginBusy && loginId.isBlank() && "accountLogout" !in loading && "accountSessionSwitch" !in loading && AccountVoiceSettingsLease.key !in loading) {
                                    Text("切换")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    target?.let { (sid, aid, title) ->
        CodexConfirmDialog(
            title = "切换账号工作区",
            message = "后续模型、用量及任务授权将使用选择的工作区，请核对目标。",
            detail = "将切换到：$title",
            confirmLabel = "确认切换",
            onConfirm = {
                vm.rpc("accountSessionSwitch", "accountSession/switch",
                    JSONObject().put("sessionId", sid).put("accountId", aid)
                        .put("allowBrowserLogin", false), after = {
                        vm.refresh("accountSessions", "accountSession/list",
                            CatalogRequestContract.accountSessions())
                        vm.refresh("account", "account/read", JSONObject())
                        vm.refresh("models", "model/list", JSONObject())
                    })
                target = null
            }, onDismiss = { target = null }
        )
    }
}
