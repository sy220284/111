package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import org.json.JSONObject

private data class RemotePairing(val code: String, val token: String, val expiresAt: Long, val epoch: Long)
private data class RemoteRevokeTarget(val environment: String, val client: JSONObject, val epoch: Long)

@Composable
internal fun RemoteControlSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val operation = rememberCapabilityOperation(vm)
    val connected by vm.bridge.status.collectProjection { it == EngineStatus.Connected }
    val session = vm.bridge.connectedSession.collectAsStateWithLifecycle().value
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var statusEpoch by remember { mutableStateOf<Long?>(null) }
    val statusOrder = remember(vm) { StatusResponseOrder() }
    var refreshPending by remember(vm) { mutableStateOf(true) }
    var clients by remember { mutableStateOf<List<JSONObject>?>(null) }
    var clientsEnvironment by remember { mutableStateOf("") }
    var clientsEpoch by remember { mutableStateOf<Long?>(null) }
    var pair by remember { mutableStateOf<RemotePairing?>(null) }
    var seconds by remember { mutableLongStateOf(0L) }
    var verified by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var revoke by remember { mutableStateOf<RemoteRevokeTarget?>(null) }
    var listError by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf("") }
    var pairReceipt by remember { mutableStateOf("") }
    val remoteStatus = status.takeIf { statusEpoch == session }?.value("status").orEmpty()
    val environment = status?.value("environmentId").orEmpty().takeIf {
        connected && remoteStatus == "connected"
    }.orEmpty()

    val visibleClients = clients.takeIf { clientsEpoch == session && clientsEnvironment == environment }
    suspend fun requestStatus(epoch: Long, fetch: suspend () -> JSONObject) {
        val token = statusOrder.begin()
        val response = fetch()
        if (epoch == vm.bridge.connectedSession.value &&
            statusOrder.accepts(token, vm.bridge.status.value == EngineStatus.Connected)) {
            statusEpoch = epoch
            status = response
        }
        verified++
    }
    fun refresh() {
        val epoch = session ?: return
        operation.launch {
            requestStatus(epoch) { vm.capabilityRequest("remoteControl/status/read", null, expectedSession = epoch) }
        }
    }
    LaunchedEffect(connected, session, operation.busy) {
        if (connected && session != null && !operation.busy && refreshPending) {
            refreshPending = false
            refresh()
        }
    }
    LaunchedEffect(vm) {
        var previous: Long? = null
        combine(vm.bridge.status, vm.bridge.connectedSession) { current, epoch -> current to epoch }.collect { (current, epoch) ->
            if (current != EngineStatus.Connected || epoch != previous) {
                statusOrder.invalidate()
                refreshPending = true
                status = null
                statusEpoch = null
                pair = null
                pairReceipt = ""
                confirm = null
                revoke = null
                clients = null
                clientsEnvironment = ""
                clientsEpoch = null
            }
            previous = epoch
        }
    }
    LaunchedEffect(vm) {
        vm.bridge.events.collect { event ->
            if (vm.bridge.isCurrentEvent(event) && event.optString("method") == "remoteControl/status/changed" &&
                vm.bridge.status.value == EngineStatus.Connected) {
                val current = event.optJSONObject("params") ?: return@collect
                statusOrder.invalidate()
                statusEpoch = event.optLong(CORE_SESSION_FIELD, -1L)
                status = current
            }
        }
    }
    LaunchedEffect(environment, session, verified) {
        clients = null
        clientsEnvironment = environment
        clientsEpoch = session
        listError = ""
        if (environment.isNotBlank() && session != null) {
            try {
                clients = capabilityPages { cursor ->
                    vm.capabilityRequest("remoteControl/client/list", capabilityPageParams(cursor)
                        .put("environmentId", environment).put("order", "desc"), expectedSession = session)
                }
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                listError = capabilityError(cancel)
            } catch (failure: Exception) {
                listError = capabilityError(failure)
            }
        }
    }
    LaunchedEffect(environment, session) {
        pair = null
        pairReceipt = ""
        pairError = ""
        revoke = null
        confirm = null
    }
    LaunchedEffect(pair, environment, session) {
        val current = pair ?: return@LaunchedEffect
        if (environment.isBlank() || session != current.epoch) return@LaunchedEffect
        try {
            var nextCheck = 0L
            while (pair === current) {
                val now = System.currentTimeMillis() / 1000
                seconds = (current.expiresAt - now).coerceAtLeast(0)
                if (seconds == 0L) {
                    pair = null
                    pairError = "配对码已过期，请生成新配对码"
                    break
                }
                if (now >= nextCheck) {
                    val result = vm.capabilityRequest("remoteControl/pairing/status",
                        JSONObject().put("pairingCode", current.token), 8_000, expectedSession = current.epoch)
                    if (result.getBoolean("claimed")) {
                        pair = null
                        pairReceipt = "配对完成，正在更新设备列表"
                        verified++
                        break
                    }
                    pairError = ""
                    nextCheck = now + 3
                }
                delay(1_000)
            }
        } catch (cancel: CancellationException) {
            if (cancel !is TimeoutCancellationException) throw cancel
            pair = null
            pairError = capabilityError(cancel)
        } catch (failure: Exception) {
            pair = null
            pairError = capabilityError(failure)
        }
    }

    CodexDetailSheet("远程控制与配对", onDismiss = { if (!operation.busy) onDismiss() }, startExpanded = true,
        subtitle = "授权其他设备连接这台手机上的 Codex。") {
        Text(when (remoteStatus) {
            "disabled" -> "远程控制已关闭"
            "connecting" -> "正在连接远程控制服务"
            "connected" -> status?.value("serverName").orEmpty().let { "远程控制已连接  $it" }.trimEnd()
            "errored" -> "远程控制连接失败"
            else -> if (connected) "等待远程控制状态" else "运行引擎未连接"
        }, style = MaterialTheme.typography.titleMedium)
        CapabilityFeedback(operation, "正在与远程控制服务通信")
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            if (remoteStatus.isNotBlank()) {
                val enable = remoteStatus == "disabled" || remoteStatus == "errored"
                CodexSecondaryButton(onClick = { session?.let { confirm = (if (enable) "enable" else "disable") to it } },
                    enabled = connected && !operation.busy) {
                    Text(if (enable) "开启远程控制" else "关闭远程控制")
                }
            }
            CodexTextAction(onClick = { refresh() }, enabled = connected && !operation.busy) { Text("刷新") }
        }
        if (environment.isNotBlank() && session != null) {
            CodexSecondaryButton(onClick = {
                operation.launch {
                    pair = null
                    pairReceipt = ""
                    pairError = ""
                    val epoch = session
                    val result = vm.capabilityRequest("remoteControl/pairing/start", JSONObject().put("manualCode", true), expectedSession = epoch)
                    check(epoch == vm.bridge.connectedSession.value && vm.bridge.status.value == EngineStatus.Connected &&
                        status?.value("status") == "connected" &&
                        status?.value("environmentId") == environment) { "配对环境已切换，请刷新状态" }
                    require(result.getString("environmentId") == environment) { "配对环境已切换，请刷新状态" }
                    val manual = result.value("manualPairingCode")
                    require(manual.isNotBlank()) { "服务未返回手动配对码，请重新生成" }
                    val expiry = result.getLong("expiresAt")
                    seconds = (expiry - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                    pair = RemotePairing(manual, result.getString("pairingCode"), expiry, epoch)
                }
            }, enabled = !operation.busy && pair == null) { Text("生成配对码") }
            pair?.takeIf { it.epoch == session }?.let {
                Text("在另一台设备的 Codex 配对界面输入此码", color = Ink.muted)
                Text(it.code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Text("剩余 ${seconds} 秒", color = Ink.muted)
            }
            if (pairError.isNotBlank()) CodexNotice(pairError, NoticeTone.Error)
            if (pairReceipt.isNotBlank()) CodexNotice(pairReceipt, NoticeTone.Success)
            HorizontalDivider()
            Text("已授权设备", style = MaterialTheme.typography.titleMedium)
            if (listError.isNotBlank()) CodexNotice(listError, NoticeTone.Error,
                actionLabel = "重试", onAction = { verified++ })
            else if (visibleClients == null) AtlasOperationProgress("正在读取授权设备")
            else if (visibleClients.isEmpty()) Text("暂无已授权设备", color = Ink.muted)
            visibleClients.orEmpty().forEach { client ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(client.value("displayName").ifBlank { client.value("deviceModel").ifBlank { "设备" } })
                        Text(listOf(client.value("platform"), client.value("deviceType"))
                            .filter(String::isNotBlank).joinToString(" · ").ifBlank { client.value("clientId") },
                            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    CodexTextAction(onClick = { revoke = RemoteRevokeTarget(environment, client, session) }, enabled = !operation.busy) {
                        Text("撤销授权", color = Ink.red)
                    }
                }
            }
        }
    }
    confirm?.takeIf { it.second == session }?.let { (action, epoch) ->
        val enable = action == "enable"
        CodexConfirmDialog(if (enable) "开启远程控制" else "关闭远程控制",
            if (enable) "开启后，已获授权的设备可以连接这台手机上的 Codex。请只与信任的设备配对。"
            else "其他设备将无法继续远程控制这台手机。",
            confirmLabel = if (enable) "开启" else "关闭",
            onConfirm = {
                confirm = null
                operation.launch {
                    pair = null
                    requestStatus(epoch) {
                        if (enable) vm.capabilityRequest("remoteControl/enable", null, expectedSession = epoch)
                        else vm.capabilityRequest("remoteControl/disable", null, expectedSession = epoch)
                    }
                }
            }, onDismiss = { confirm = null })
    }
    revoke?.takeIf { it.epoch == session && it.environment == environment && environment.isNotBlank() }?.let { target ->
        val client = target.client
        CodexConfirmDialog("撤销设备授权", "该设备需重新配对才能再次连接。",
            detail = client.value("displayName").ifBlank { client.value("clientId") },
            confirmLabel = "撤销授权", destructive = true,
            onConfirm = {
                revoke = null
                operation.launch {
                    require(vm.bridge.status.value == EngineStatus.Connected &&
                        status?.value("status") == "connected" &&
                        status?.value("environmentId") == target.environment) {
                        "远程控制环境已变更，请刷新状态"
                    }
                    vm.capabilityRequest("remoteControl/client/revoke", JSONObject()
                        .put("environmentId", target.environment).put("clientId", client.getString("clientId")), expectedSession = target.epoch)
                    operation.receipt = "已撤销设备授权"
                    verified++
                }
            }, onDismiss = { revoke = null })
    }
}
