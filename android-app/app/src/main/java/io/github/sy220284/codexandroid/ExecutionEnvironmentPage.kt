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

    fun resolvedName(endpoint: String, customName: String): String =
        customName.trim().ifBlank { defaultName(endpoint) }

    fun supportsRemoteName(name: String): Boolean =
        name.isNotBlank() && !name.trim().equals("local", ignoreCase = true)

    fun statusLabel(status: String): String = when (status) {
        "ready" -> "已连接"
        "pending" -> "已登记，等待建立连接"
        "disconnected" -> "连接断开"
        "unknown" -> "未登记或服务不可用"
        else -> "状态待核实"
    }

    fun supportsEndpoint(raw: String): Boolean {
        val uri = try {
            URI(raw.trim())
        } catch (ex: URISyntaxException) {
            return false
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.rawQuery != null ||
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
fun EnvironmentsPage(vm: CodexViewModel, onRemoteTask: () -> Unit = {}) {
    val results by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val session by vm.bridge.connectedSession.collectAsState()
    var remoteControl by remember { mutableStateOf(false) }
    var useRemote by rememberSaveable { mutableStateOf(false) }
    val target = remember { SettingsDeepLink.take("environments") }
    LaunchedEffect(target) {
        if (target == "remote") useRemote = true
    }
    var customRemoteName by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    val remoteName = ExecutionEnvironmentConnectionPolicy.resolvedName(endpoint, customRemoteName)
    var editingField by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val targetId = if (useRemote) remoteName.trim() else "local"
    var queriedEnvironment by remember(session, targetId, endpoint) { mutableStateOf<String?>(null) }
    var lastSubmittedRemote by remember(session, targetId, endpoint) { mutableStateOf<String?>(null) }
    val canConnect = ExecutionEnvironmentConnectionPolicy.supportsRemoteName(remoteName) &&
        ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint) && vm.remoteBindings.permitsRegistration(remoteName.trim(), endpoint.trim())
    val statusKey = "envStatus:$session:$targetId"
    val infoKey = "envInfo:$session:$targetId"
    val addKey = "environmentAdd:$session:$targetId"
    val statusBusy = statusKey in loading
    val infoBusy = infoKey in loading
    val writeBusy = loading.any { it.startsWith("environmentAdd:$session:") } ||
        statusBusy || infoBusy

    fun checkEnvironment(id: String) {
        queriedEnvironment = id
        vm.refresh("envStatus:$session:$id", "environment/status", JSONObject().put("environmentId", id))
    }
    fun loadEnvironmentInfo(id: String) {
        queriedEnvironment = id
        vm.refresh("envInfo:$session:$id", "environment/info", JSONObject().put("environmentId", id),
            onSuccess = { if (vm.bridge.connectedSession.value == session) checkEnvironment(id) })
    }

    if (remoteControl) RemoteControlSheet(vm) { remoteControl = false }
    ScreenColumn {
        SectionIntro("运行管理",
            "管理本机执行环境，并登记和检查已部署的外部执行服务。")
        CodexSecondaryButton(onClick = { remoteControl = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote-control-entry")) { Text("远程控制与配对") }
        Text("执行环境", style = MaterialTheme.typography.titleLarge)
        Text("本机任务使用手机工作区；外部设备连接后可以建立独立远端任务。", color = Ink.muted)
        CodexSecondaryButton(
            onClick = {
                editingField = null
                focus.clearFocus()
                keyboard?.hide()
                useRemote = false
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .testTag("environment-local-option")
        ) {
            CodexIcon(CodexGlyph.PhoneAndroid, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("在这台手机上执行", style = MaterialTheme.typography.titleMedium)
                Text("使用 Download/Codex 手机共享工作区", style = MaterialTheme.typography.labelMedium,
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
                ActionQuestionRow(endpoint, { endpoint = it.trim() }, "安全连接地址（WebSocket）",
                    enabled = !writeBusy, placeholder = "wss://example.com/exec",
                    confirmedValid = endpoint.isNotBlank() && ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint),
                    helper = "使用 wss:// 加密连接；本机回环地址可使用 ws://127.0.0.1",
                    error = if (endpoint.isNotBlank() && !ExecutionEnvironmentConnectionPolicy.supportsEndpoint(endpoint))
                        "连接地址格式不正确，或不符合安全连接要求。" else null,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    validate = { address -> if (address.isNotBlank() && !ExecutionEnvironmentConnectionPolicy.supportsEndpoint(address))
                        "连接地址格式不正确，或不符合安全连接要求。" else null },
                    modifier = Modifier.testTag("environment-endpoint"), replyTag = "environment-address-reply",
                    expanded = editingField == "address", onExpandedChange = { editingField = if (it) "address" else null })
                if (remoteName.isNotBlank()) ActionQuestionRow(remoteName, { customRemoteName = it.trim() },
                    "连接名称", helper = "根据地址自动生成，可按需修改；留空恢复自动名称",
                    enabled = !writeBusy, placeholder = "例如：工作电脑", modifier = Modifier.testTag("environment-remote-name"),
                    validate = { name -> if (name.trim().equals("local", ignoreCase = true)) "local 为本机保留名称" else null },
                    replyTag = "environment-name-reply", expanded = editingField == "name",
                    onExpandedChange = { editingField = if (it) "name" else null })
                CodexSecondaryButton(
                    onClick = {
                        val id = remoteName.trim()
                        val url = endpoint.trim()
                        lastSubmittedRemote = id
                        queriedEnvironment = id
                        vm.data.value = vm.data.value - addKey - statusKey - infoKey
                        vm.errors.value = vm.errors.value - addKey - statusKey - infoKey
                        vm.rpc(addKey, "environment/add",
                            JSONObject().put("environmentId", id).put("execServerUrl", url),
                            after = { if (vm.bridge.connectedSession.value == session) loadEnvironmentInfo(id) })
                    },
                    enabled = canConnect && !writeBusy && editingField == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("environment-connect")
                ) {
                    CodexIcon(CodexGlyph.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (writeBusy) "正在验证连接" else "添加并检查连接")
                }

                if (lastSubmittedRemote == targetId) {
                    if (writeBusy) AtlasOperationProgress("正在登记并读取真实执行服务")
                    PresentationPolicy.visibleErrors(errors, listOf(addKey, infoKey, statusKey))
                        .forEach { ErrorText(it) }
                    results[statusKey]?.let { response ->
                        val state = response.optString("status")
                        StatusPill(ExecutionEnvironmentConnectionPolicy.statusLabel(state), state == "ready")
                        response.optString("error").takeIf { it.isNotBlank() }?.let { ErrorText(it) }
                    }
                    results[infoKey]?.let { response ->
                        response.optJSONObject("shell")?.optString("name")?.takeIf { it.isNotBlank() }
                            ?.let { Text("远端 Shell：$it", color = Ink.muted) }
                        PresentationPolicy.meaningful(response.optString("cwd")).takeIf { it.isNotEmpty() }
                            ?.let { Text("远端工作目录：$it", color = Ink.muted) }
                    }
                    if (results[addKey] != null && !writeBusy && results[statusKey] == null &&
                        errors[infoKey] == null && errors[statusKey] == null)
                        CodexNotice("服务已登记，连接状态待核实。", NoticeTone.Info)
                }
                if (!vm.remoteBindings.permitsRegistration(remoteName.trim(), endpoint.trim()))
                    ErrorText("该连接名称已绑定原设备，请使用原地址或选择新的名称。")
                val bindingResult = remember(targetId, endpoint, lastSubmittedRemote, results[infoKey], results[statusKey]) {
                    if (lastSubmittedRemote == targetId && results[statusKey]?.optString("status") == "ready")
                        runCatching { RemoteExecutionBinding(targetId, endpoint.trim(),
                            RemoteExecutionBinding.nativePath(requireNotNull(results[infoKey]).getString("cwd"))) }
                    else null
                }
                bindingResult?.exceptionOrNull()?.let { ErrorText(it.message ?: "远端工作目录无法核对") }
                val binding = bindingResult?.getOrNull()
                if (binding != null) CodexPrimaryButton(onClick = {
                    if (vm.openRemoteTask(binding)) onRemoteTask()
                    else vm.errors.value = vm.errors.value + (addKey to "当前无法建立远端任务，请检查运行状态")
                }, enabled = !writeBusy && editingField == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("remote-task-start")) {
                    Text("在此设备新建任务")
                }
                Text("远端任务固定执行设备及工作目录。手机附件不会自动传输；远端文件及命令交由智能体处理。", color = Ink.muted,
                    style = MaterialTheme.typography.bodySmall)
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
                    listOf(statusKey, infoKey, addKey)
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
                            if (key == statusKey) {
                                val state = response.optString("status")
                                StatusPill(ExecutionEnvironmentConnectionPolicy.statusLabel(state), state == "ready")
                                response.optString("error").takeIf { it.isNotBlank() }?.let { ErrorText(it) }
                            } else {
                                response.optJSONObject("shell")?.let { shell ->
                                    Text("Shell：" + shell.optString("name") + " · " + shell.optString("path"))
                                }
                                PresentationPolicy.meaningful(response.optString("cwd")).takeIf { it.isNotEmpty() }
                                    ?.let { Text("工作目录：$it", color = Ink.muted) }
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
        DeveloperToolStatus(vm)
        EngineSettingsSection(vm, "environments", target)
    }
}
