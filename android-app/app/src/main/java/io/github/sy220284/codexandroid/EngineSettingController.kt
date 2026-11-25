package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Keep the full write/read transaction owned by the ViewModel, including navigation away. */
internal const val ENGINE_SETTING_SAVE = "engineSettingSave"
internal fun configSnapshotBusy(loading: Set<String>, permissionBusy: Boolean) =
    loading.any { it in setOf("config", ENGINE_SETTING_SAVE, "appMutation", "skillsWrite", "configWrite", "modelProviderWrite", "providerWrite", "providerDefaultWrite") } || permissionBusy
internal fun CodexViewModel.configBusy() =
    configSnapshotBusy(loading.value, securitySaveBusy.value || configTransactionBusy.value)
internal fun CodexViewModel.saveEngineSetting(path: String, value: Any, title: String, owner: String = "config",
    saveKey: String = ENGINE_SETTING_SAVE, requireSnapshot: Boolean = true,
    expectedVersion: String? = null, after: () -> Unit = {}) {
    if (DataTransferMutationGate.refuse(this, saveKey, "config/value/write")) return
    if (configBusy()) {
        errors.value = errors.value + (saveKey to "已有配置操作正在处理，请完成后再保存")
        viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure("config/value/write", "已有配置操作正在处理，请完成后再保存")) }
        return
    }
    if (bridge.status.value != EngineStatus.Connected || (requireSnapshot && data.value["config"]?.optJSONObject("config") == null)) {
        data.value = data.value + (saveKey to JSONObject().put("owner", owner).put("keyPath", path))
        errors.value = errors.value + (saveKey to "请先连接并读取当前配置")
        return
    }
    val generation = configGeneration
    configTransactionBusy.value = true
    loading.value = loading.value + saveKey
    data.value = data.value + (saveKey to JSONObject().put("owner", owner).put("keyPath", path))
    errors.value = errors.value - saveKey
    viewModelScope.launch {
        var readObtained = false
        var needsRefresh = false
        try {
            ConfigWriteTransaction.execute(
                write = { bridge.request("config/value/write", EngineSettings.writeParams(path, value).apply {
                    expectedVersion?.let { put("expectedVersion", it) }
                }) },
                read = { bridge.request("config/read", JSONObject().put("includeLayers", true)) },
                isCurrent = { configGeneration == generation },
                publish = { read ->
                    readObtained = true
                    data.value = data.value + ("config" to read)
                    errors.value = errors.value - "config"
                },
                verify = { write, read -> EngineSettings.verify(write, read, path.trim(), value) })
            data.value = data.value + (saveKey to JSONObject().put("owner", owner).put("keyPath", path).put("message", "$title 已保存并核实"))
        } catch (timeout: TimeoutCancellationException) {
            errors.value = errors.value + (saveKey to "配置保存或回读超时，请核对实际设置")
            needsRefresh = !readObtained
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            errors.value = errors.value + (saveKey to (failure.message ?: "保存或回读失败，请重新读取配置"))
            needsRefresh = !readObtained
        } finally {
            configTransactionBusy.value = false
            loading.value = loading.value - saveKey
        }
        if (generation != configGeneration) {
            errors.value = errors.value - saveKey
            refresh("config", "config/read", JSONObject().put("includeLayers", true))
            refreshComposerPermissions()
            return@launch
        }
        if (needsRefresh) refresh("config", "config/read", JSONObject().put("includeLayers", true))
        refreshComposerPermissions()
        if (saveKey != ENGINE_SETTING_SAVE) {
            val failure = errors.value[saveKey]
            actionFeedback.emit(if (failure == null) ActionFeedbackReceipt("$title 已保存并核实")
                else ActionFeedbackCopy.failure("config/value/write", failure))
        }
        after()
    }
}
