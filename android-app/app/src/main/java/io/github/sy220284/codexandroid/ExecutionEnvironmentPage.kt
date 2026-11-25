package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.net.URI
import java.net.URISyntaxException
import org.json.JSONObject

/** Validates the connection scheme and destination before enabling remote submission. */
internal object ExecutionEnvironmentConnectionPolicy {
    fun defaultName(raw: String): String {
        if (!supportsEndpoint(raw)) return ""
        val uri = URI(raw.trim())
        return uri.host + (if (uri.port > 0) "-${uri.port}" else "")
    }

    fun supportsEndpoint(raw: String): Boolean {
        val uri = try {
            URI(raw.trim())
        } catch (ex: URISyntaxException) {
            return false
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
            uri.port == 0 || uri.port > 65535) return false
        return uri.scheme.equals("wss", ignoreCase = true) ||
            (uri.scheme.equals("ws", ignoreCase = true) &&
                uri.host == "127.0.0.1" && uri.port in 1..65535)
    }
}

/**
 * One decision at a time: choose the execution location before seeing a connection form.
 * Remote environments use the existing official environment/add, info and status RPCs.
 */
@Composable
fun EnvironmentsPage(vm: CodexViewModel) {
    val results by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var remoteControl by remember { mutableStateOf(false) }
    var useRemote by rememberSaveable { mutableStateOf(false) }
    val target = remember { SettingsDeepLink.take("environments") }
    LaunchedEffect(target) {
        if (target == "remote") useRemote = true
    }
    var remoteName by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var queriedEnvironment by remember { mutableStateOf<String?>(null) }
    var lastSubmittedRemote by remember { mutableStateOf<String?>(null) }
    val targetId = if (useRemote) remoteName.trim() else "local"
    val canConnect = remoteName.isNotBlank() &&
        ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint)
    val statusKey = "envStatus:$targetId"
    val infoKey = "envInfo:$targetId"
    val statusBusy = statusKey in loading
    val infoBusy = infoKey in loading
    val writeBusy = "environmentAdd" in loading

    fun checkEnvironment(id: String) {
        queriedEnvironment = id
        vm.refresh("envStatus:$id", "environment/status", JSONObject().put("environmentId", id))
    }
    fun loadEnvironmentInfo(id: String) {
        queriedEnvironment = id
        vm.refresh("envInfo:$id", "environment/info", JSONObject().put("environmentId", id))
    }

    if (remoteControl) RemoteControlSheet(vm) { remoteControl = false }
    ScreenColumn {
        SectionIntro("运行管理",
            "选择工作在这台手机上完成，或连接已部署的外部执行服务。")
        CodexSecondaryButton(onClick = { remoteControl = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote-control-entry")) { Text("远程控制与配对") }
        Text("在哪里执行任务", style = MaterialTheme.typography.titleLarge)
        Text("先选择运行位置，外部设备采用逐步连接。", color = Ink.muted)
        CodexSecondaryButton(
            onClick = { useRemote = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-local-option")
        ) {
            CodexIcon(CodexGlyph.PhoneAndroid, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("在这台手机上执行", style = MaterialTheme.typography.titleMedium)
                Text("使用应用私有工作区", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
            if (!useRemote) CodexIcon(CodexGlyph.TaskComplete, "已选择")
        }
        CodexSecondaryButton(
            onClick = { useRemote = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-remote-option")
        ) {
            CodexIcon(CodexGlyph.Computer, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("连接其他设备", style = MaterialTheme.typography.titleMedium)
                Text("使用兼容的外部执行服务", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
            if (useRemote) CodexIcon(CodexGlyph.TaskComplete, "已选择")
        }

        if (useRemote) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("连接外部执行服务", style = MaterialTheme.typography.titleMedium)
                Text("需要预先在另一台设备部署兼容的执行服务，并获得连接地址。",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                ActionQuestionRow(endpoint, { address ->
                    val oldName = ExecutionEnvironmentConnectionPolicy.defaultName(endpoint)
                    endpoint = address
                    if (remoteName.isBlank() || remoteName == oldName)
                        remoteName = ExecutionEnvironmentConnectionPolicy.defaultName(address)
                }, "安全连接地址（WebSocket）",
                    placeholder = "wss://example.com/exec",
                    confirmedValid = endpoint.isNotBlank() && ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint),
                    helper = "使用 wss:// 加密连接；本机回环地址可使用 ws://127.0.0.1",
                    error = if (endpoint.isNotBlank() && !ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint))
                        "连接地址格式不正确，或不符合安全连接要求。" else null,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    modifier = Modifier.testTag("environment-endpoint"))
                if (remoteName.isNotBlank()) ActionQuestionRow(remoteName, { remoteName = it.trim().ifBlank { ExecutionEnvironmentConnectionPolicy.defaultName(endpoint) } },
                    "连接名称", helper = "根据地址自动生成，可按需修改",
                    placeholder = "例如：工作电脑", modifier = Modifier.testTag("environment-remote-name"))
                CodexSecondaryButton(
                    onClick = {
                        val id = remoteName.trim()
                        val url = endpoint.trim()
                        lastSubmittedRemote = id
                        queriedEnvironment = id
                        vm.data.value = vm.data.value - "environmentAdd"
                        vm.rpc("environmentAdd", "environment/add",
                            JSONObject().put("environmentId", id).put("execServerUrl", url),
                            after = { checkEnvironment(id) })
                    },
                    enabled = canConnect && !writeBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("environment-connect")
                ) {
                    CodexIcon(CodexGlyph.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (writeBusy) "正在提交连接" else "添加并检查连接")
                }

                if (results["environmentAdd"] != null && lastSubmittedRemote == targetId)
                    CodexNotice("连接请求已被受理，正在读取执行服务状态。", NoticeTone.Info)
            }
        }

        CodexDetailTrigger(
            label = "查看环境状态与详细诊断",
            onClick = { showAdvanced = true },
            modifier = Modifier.testTag("environment-advanced-toggle")
        )
        if (showAdvanced) CodexDetailSheet(
            title = "执行环境检查", startExpanded = true,
            subtitle = "查看实际连接状态、服务信息与请求结果",
            onDismiss = { showAdvanced = false }
        ) {
            Text("当前环境：" +
                if (useRemote) remoteName.ifBlank { "尚未选择连接" } else "本机",
                style = MaterialTheme.typography.bodyMedium, color = Ink.parchment)
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CodexSecondaryButton(
                    onClick = { checkEnvironment(targetId) },
                    enabled = targetId.isNotBlank() && !statusBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("environment-check-status")
                ) { Text(if (statusBusy) "查询中" else "检查状态") }
                CodexSecondaryButton(
                    onClick = { loadEnvironmentInfo(targetId) },
                    enabled = targetId.isNotBlank() && !infoBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("environment-info")
                ) { Text(if (infoBusy) "读取中" else "查看详情") }
            }
            if (queriedEnvironment == targetId || (useRemote && lastSubmittedRemote == targetId)) {
                val visibleKeys = if (useRemote && lastSubmittedRemote == targetId)
                    listOf(statusKey, infoKey, "environmentAdd")
                else listOf(statusKey, infoKey)
                PresentationPolicy.visibleErrors(errors, visibleKeys)
                    .forEach { error -> ErrorText(error) }
            }
            if (queriedEnvironment == targetId) {
                if (statusBusy) AtlasOperationProgress("正在查询环境状态")
                if (infoBusy) AtlasOperationProgress("正在读取环境信息")
                for (key in listOf(statusKey, infoKey)) {
                    results[key]?.let { response ->
                        MaterialPanel(Modifier.fillMaxWidth()) {
                            Text(if (key == statusKey) "环境运行状态" else "环境详情",
                                style = MaterialTheme.typography.titleMedium)
                            val state = PresentationPolicy.serviceState(response.optString("status"))
                            if (key == statusKey) StatusPill(state, state == "已连接")
                            PresentationPolicy.meaningful(response.optString("displayName"))
                                .takeIf { it.isNotEmpty() }
                                ?.let { Text(it, color = Ink.muted) }
                            if (key == infoKey) {
                                Text("详细状态已读取，可在执行服务端查看运行日志。",
                                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (!statusBusy && !infoBusy && results[statusKey] == null &&
                    results[infoKey] == null && errors[statusKey] == null &&
                    errors[infoKey] == null) {
                    AtlasFieldFeedback("请选择检查状态或查看详情")
                }
            } else {
                AtlasFieldFeedback("请选择检查状态或查看详情以获取最新信息")
            }
        }
        EngineSettingsSection(vm, "environments", target)
    }
}
