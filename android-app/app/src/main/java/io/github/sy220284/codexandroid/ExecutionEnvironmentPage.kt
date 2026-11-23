package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
    var useRemote by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SettingsDeepLink.take("environments") == "remote") useRemote = true
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

    ScreenColumn {
        SectionIntro("EXECUTION / ENVIRONMENT", "执行环境",
            "选择工作在这台手机上完成，或连接已部署的外部执行服务。")
        Text("在哪里执行任务", style = MaterialTheme.typography.titleLarge)
        Text("先选择运行位置，需要连接时再填写配置。", color = Ink.muted)
        OutlinedButton(
            onClick = { useRemote = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-local-option")
        ) {
            Icon(Icons.Outlined.PhoneAndroid, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("在这台手机上执行", style = MaterialTheme.typography.titleMedium)
                Text("使用应用私有工作区", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
            if (!useRemote) Icon(Icons.Outlined.CheckCircle, "已选择")
        }
        OutlinedButton(
            onClick = { useRemote = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-remote-option")
        ) {
            Icon(Icons.Outlined.Computer, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("连接其他设备", style = MaterialTheme.typography.titleMedium)
                Text("使用兼容的外部执行服务", style = MaterialTheme.typography.labelMedium,
                    color = Ink.muted)
            }
            if (useRemote) Icon(Icons.Outlined.CheckCircle, "已选择")
        }

        if (useRemote) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("连接外部执行服务", style = MaterialTheme.typography.titleMedium)
                Text("需要预先在另一台设备部署兼容的执行服务，并获得连接地址。",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                OutlinedTextField(
                    value = remoteName, onValueChange = { remoteName = it },
                    label = { Text("连接名称") },
                    placeholder = { Text("例如：工作电脑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("environment-remote-name")
                )
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("安全连接地址（WebSocket）") },
                    placeholder = { Text("wss://example.com/exec") },
                    supportingText = {
                        Text("使用 wss:// 加密连接；本机回环地址可使用 ws://127.0.0.1")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("environment-endpoint")
                )
                OutlinedButton(
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
                    Icon(Icons.Outlined.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (writeBusy) "正在提交连接" else "添加并检查连接")
                }
                if (endpoint.isNotBlank() && !canConnect) {
                    Text("请填写连接名称和安全的服务地址，才能提交连接请求。",
                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                if (results["environmentAdd"] != null && lastSubmittedRemote == targetId)
                    CodexNotice("连接请求已被受理，正在读取执行服务状态。", NoticeTone.Info)
            }
        }

        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.testTag("environment-advanced-toggle")
        ) {
            Icon(Icons.Outlined.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showAdvanced) "收起详细诊断" else "查看环境状态与详细诊断")
        }
        CodexReveal(showAdvanced) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("环境检查", style = MaterialTheme.typography.titleMedium)
                Text("当前环境：${if (useRemote) remoteName.ifBlank { "请先填写连接名称" } else "本机"}",
                    color = Ink.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { checkEnvironment(targetId) },
                        enabled = targetId.isNotBlank() && !statusBusy,
                        modifier = Modifier.testTag("environment-check-status")) {
                        Text(if (statusBusy) "查询中" else "检查状态")
                    }
                    OutlinedButton(onClick = { loadEnvironmentInfo(targetId) },
                        enabled = targetId.isNotBlank() && !infoBusy,
                        modifier = Modifier.testTag("environment-info")) {
                        Text(if (infoBusy) "读取中" else "查看详情")
                    }
                }
            }
        }
        val visibleKeys = if (useRemote && lastSubmittedRemote == targetId)
            listOf(statusKey, infoKey, "environmentAdd")
        else listOf(statusKey, infoKey)
        if (queriedEnvironment == targetId || (useRemote && lastSubmittedRemote == targetId)) {
            PresentationPolicy.visibleErrors(errors, visibleKeys)
                .forEach { error -> ErrorText(error) }
        }
        for (key in listOf(statusKey, infoKey)) {
            if (queriedEnvironment == targetId) {
                results[key]?.let { response ->
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Text(if (key == statusKey) "环境运行状态" else "环境详情",
                            style = MaterialTheme.typography.titleMedium)
                        val state = PresentationPolicy.serviceState(response.optString("status"))
                        if (key == statusKey) {
                            StatusPill(state, state == "已连接")
                        }
                        PresentationPolicy.meaningful(response.optString("displayName"))
                            .takeIf { it.isNotEmpty() }
                            ?.let { Text(it, color = Ink.muted) }
                        if (key == infoKey)
                            Text("详细状态已读取，可在服务端查看配置与运行日志。",
                                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
