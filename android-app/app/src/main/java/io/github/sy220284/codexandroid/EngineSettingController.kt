package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Keep the full write/read transaction owned by the ViewModel, including navigation away. */
internal const val ENGINE_SETTING_SAVE = "engineSettingSave"
internal fun configSnapshotBusy(loading: Set<String>, permissionBusy: Boolean) =
    loading.any { it in setOf("config", ENGINE_SETTING_SAVE, "appMutation", "skillsWrite", "configWrite", "modelProviderWrite") } || permissionBusy
internal fun CodexViewModel.saveEngineSetting(path: String, value: Any, title: String, owner: String = "config",
    saveKey: String = ENGINE_SETTING_SAVE, requireSnapshot: Boolean = true, after: () -> Unit = {}) {
    if (DataTransferMutationGate.refuse(this, saveKey, "config/value/write")) return
    if (configSnapshotBusy(loading.value, securitySaveBusy.value)) {
        viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure("config/value/write", "已有配置操作正在处理，请完成后再保存")) }
        return
    }
    if (bridge.status.value != EngineStatus.Connected || (requireSnapshot && data.value["config"]?.optJSONObject("config") == null)) {
        data.value = data.value + (saveKey to JSONObject().put("owner", owner).put("keyPath", path))
        errors.value = errors.value + (saveKey to "请先连接并读取当前配置")
        return
    }
    loading.value = loading.value + saveKey
    data.value = data.value + (saveKey to JSONObject().put("owner", owner).put("keyPath", path))
    errors.value = errors.value - saveKey
    viewModelScope.launch {
        var readObtained = false
        var needsRefresh = false
        try {
            val write = bridge.request("config/value/write", EngineSettings.writeParams(path, value))
            val read = bridge.request("config/read", JSONObject().put("includeLayers", true))
            read.getJSONObject("config")
            readObtained = true
            data.value = data.value + ("config" to read)
            errors.value = errors.value - "config"
            EngineSettings.verify(write, read, path.trim(), value)
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
            loading.value = loading.value - saveKey
        }
        if (needsRefresh) refresh("config", "config/read", JSONObject().put("includeLayers", true))
        refreshComposerPermissions()
        after()
    }
}
