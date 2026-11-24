package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Keep the full write/read transaction owned by the ViewModel, including navigation away. */
internal const val ENGINE_SETTING_SAVE = "engineSettingSave"
internal fun CodexViewModel.saveEngineSetting(path: String, value: Any, title: String) {
    if (DataTransferMutationGate.refuse(this, ENGINE_SETTING_SAVE, "config/value/write")) return
    if (ENGINE_SETTING_SAVE in loading.value || "config" in loading.value || securitySaveBusy.value) return
    if (bridge.status.value != EngineStatus.Connected || data.value["config"]?.optJSONObject("config") == null) {
        errors.value = errors.value + (ENGINE_SETTING_SAVE to "请先连接并读取当前配置")
        return
    }
    loading.value = loading.value + ENGINE_SETTING_SAVE
    data.value = data.value - ENGINE_SETTING_SAVE
    errors.value = errors.value - ENGINE_SETTING_SAVE
    viewModelScope.launch {
        try {
            val write = bridge.request("config/value/write", EngineSettings.writeParams(path, value))
            val read = bridge.request("config/read", JSONObject().put("includeLayers", true))
            data.value = data.value + ("config" to read)
            EngineSettings.verify(write, read, path.trim(), value)
            data.value = data.value + (ENGINE_SETTING_SAVE to JSONObject().put("message", "$title 已保存并核实"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            errors.value = errors.value + (ENGINE_SETTING_SAVE to (failure.message ?: "保存或回读失败，请重新读取配置"))
            refresh("config", "config/read", JSONObject().put("includeLayers", true))
        } finally {
            loading.value = loading.value - ENGINE_SETTING_SAVE
        }
    }
}
