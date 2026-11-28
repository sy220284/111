package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

internal data class AccountVoiceOption(val voice: String, val name: String)
internal data class AccountVoiceSettingsSnapshot(val selected: String, val voices: List<AccountVoiceOption>) {
    fun writeParams(voice: String): JSONObject {
        require(voices.any { it.voice == voice }) { "当前账号未提供该声音选项" }
        return JSONObject().put("voice", voice)
    }

    companion object {
        fun parse(result: JSONObject): AccountVoiceSettingsSnapshot {
            val selected = result.get("selected") as? String ?: error("账号声音选择无效")
            require(selected.isNotBlank()) { "账号未返回当前声音" }
            val array = result.getJSONArray("voices")
            val options = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                val voice = entry.get("voice") as? String ?: error("声音标识无效")
                val name = entry.get("name") as? String ?: error("声音名称无效")
                require(voice.isNotBlank() && name.isNotBlank()) { "声音选项无效" }
                AccountVoiceOption(voice, name)
            }
            require(options.map { it.voice }.distinct().size == options.size) { "声音目录存在重复选项" }
            return AccountVoiceSettingsSnapshot(selected, options)
        }
    }
}

/** The upstream runtime uses the account voice preference; no per-session voice override. */
@Composable
internal fun AccountVoiceSettings(vm: CodexViewModel, identity: String, epoch: Long, revision: Long) {
    val operation = rememberCapabilityOperation(vm)
    var snapshot by remember { mutableStateOf<AccountVoiceSettingsSnapshot?>(null) }
    var menu by remember { mutableStateOf(false) }
    val engine by vm.bridge.status.collectAsStateWithLifecycle()
    val session by vm.bridge.connectedSession.collectAsStateWithLifecycle()
    val accountRevision by vm.catalogWarmup.accountRevision.collectAsStateWithLifecycle()
    val data by vm.data.collectEntries("account")
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loginBusy by vm.loginBusy.collectAsStateWithLifecycle()
    val loginId by vm.activeLoginId.collectAsStateWithLifecycle()
    val transfer by AppDataTransfer.state.collectAsStateWithLifecycle()
    val canOperate = engine == EngineStatus.Connected && session == epoch && accountRevision == revision &&
        data["account"]?.optJSONObject("account")?.toString() == identity &&
        "account" !in loading && "accountSessionSwitch" !in loading && !loginBusy &&
        loginId.isBlank() && !transfer.busy && AccountVoiceSettingsLease.key !in loading

    fun ownsAccount(): Boolean =
        vm.bridge.status.value == EngineStatus.Connected &&
            vm.bridge.connectedSession.value == epoch &&
            vm.catalogWarmup.accountRevision.value == revision &&
            vm.data.value["account"]?.optJSONObject("account")?.toString() == identity &&
            "account" !in vm.loading.value && "accountSessionSwitch" !in vm.loading.value &&
            !vm.loginBusy.value &&
            vm.activeLoginId.value.isBlank() && !AppDataTransfer.state.value.busy

    fun launchAccountOperation(block: suspend () -> Unit) {
        if (operation.busy || !ownsAccount() || !AccountVoiceSettingsLease.acquire(vm.loading)) return
        operation.launch(onRejected = { AccountVoiceSettingsLease.release(vm.loading) }) {
            try { block() } finally { AccountVoiceSettingsLease.release(vm.loading) }
        }
    }

    suspend fun read(): AccountVoiceSettingsSnapshot {
        check(ownsAccount()) { "账号或内核已切换，请刷新账号状态" }
        val result = vm.capabilityRequest("account/voiceSettings/read", null,
            sendPermit = ::ownsAccount, expectedSession = epoch)
        check(ownsAccount()) { "账号已切换，已丢弃旧声音目录" }
        return AccountVoiceSettingsSnapshot.parse(result)
    }

    Column(Modifier.fillMaxWidth().testTag("account-voice-settings"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ChatGPT 账号默认声音", style = MaterialTheme.typography.titleSmall)
        Text("修改会同步到当前 ChatGPT 账号，供后续实时语音使用。",
            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        Box {
            CodexSecondaryButton(
                onClick = {
                    launchAccountOperation {
                        snapshot = null
                        snapshot = read()
                        menu = snapshot?.voices?.isNotEmpty() == true
                    }
                },
                enabled = !operation.busy && canOperate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                val current = snapshot
                Text(if (current == null) "读取并选择默认声音" else
                    "当前：" + (current.voices.firstOrNull { it.voice == current.selected }?.name ?: current.selected))
            }
            CodexPopupMenu(menu && canOperate, { menu = false }) {
                snapshot?.voices.orEmpty().forEach { option ->
                    DropdownMenuItem(text = {
                        Text(if (option.voice == snapshot?.selected) option.name + " · 当前" else option.name)
                    }, onClick = {
                        menu = false
                        val current = snapshot
                        if (current != null && option.voice != current.selected) launchAccountOperation {
                            val params = current.writeParams(option.voice)
                            check(ownsAccount()) { "账号已切换，请重新读取声音目录" }
                            snapshot = null
                            vm.capabilityRequest("account/voiceSettings/write", params,
                                sendPermit = ::ownsAccount, expectedSession = epoch)
                            val verified = read()
                            snapshot = verified
                            check(verified.selected == option.voice) { "账号返回的声音与所选声音不一致" }
                            operation.receipt = "账号默认声音已保存并核实"
                        }
                    })
                }
            }
        }
        if (snapshot?.voices?.isEmpty() == true) Text("当前账号没有返回可选声音",
            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        CapabilityFeedback(operation, "正在同步账号声音设置")
    }
}
