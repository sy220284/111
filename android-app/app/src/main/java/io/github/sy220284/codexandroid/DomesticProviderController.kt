package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Owns credential changes independently of the settings page lifecycle. */
internal class DomesticProviderController(private val vm: CodexViewModel) {
    private val context get() = vm.getApplication<Application>()
    private val lease get() = vm.connectionRestarter.busy

    private fun acquire(): Boolean {
        val snapshot = vm.globalWorkSnapshot()
        if (!snapshot.canDisruptCoreOrData || vm.configBusy() ||
            vm.bridge.status.value != EngineStatus.Connected || !lease.compareAndSet(false, true)) {
            vm.errors.value = vm.errors.value + ("providerWrite" to
                "请在内核连接且全部任务、审批和配置操作结束后变更授权")
            return false
        }
        vm.configTransactionBusy.value = true
        vm.errors.value = vm.errors.value - "providerWrite"
        return true
    }

    private fun release() {
        vm.configTransactionBusy.value = false
        lease.value = false
    }

    private suspend fun restart() {
        withContext(Dispatchers.IO) { vm.bridge.close() }
        vm.connect().join()
        check(vm.bridge.status.value == EngineStatus.Connected) {
            "配置已保存，内核重新连接失败；请重新连接后验证授权"
        }
    }

    private fun refresh() {
        vm.refresh("providers", "modelProvider/list")
        vm.refresh("models", "model/list")
        vm.refresh("account", "account/read")
    }

    fun configure(preset: DomesticModelPreset, name: String, key: String, after: () -> Unit) {
        if (name.isBlank() || (key.isNotBlank() && !DomesticProviderCredentials.valid(key))) {
            vm.errors.value = vm.errors.value + ("providerWrite" to "服务商名称或密钥格式无效")
            return
        }
        if (!acquire()) return
        vm.viewModelScope.launch {
            var encryptedBefore: ByteArray? = null
            var keySaved = false
            var configVerified = false
            try {
                val current = vm.bridge.request("modelProvider/list", JSONObject())
                val matching = records(current).filter { preset.ownsProvider(it) }
                check(matching.size <= 1) { "存在重复的预置服务商配置，请先整理" }
                val existing = matching.singleOrNull()
                check(existing == null || existing.optBoolean("canEdit")) { "当前预置服务商配置只读" }
                if (key.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        encryptedBefore = DomesticProviderCredentials.snapshot(context, preset)
                        DomesticProviderCredentials.save(context, preset, key)
                        keySaved = true
                    }
                } else {
                    check(DomesticProviderCredentials.has(context, preset)) { "请先填写 API Key" }
                }
                val patch = JSONObject().put("type", "customGateway")
                    .put("name", name.trim()).put("baseUrl", preset.baseUrl).put("envKey", preset.envKey)
                existing?.let { patch.put("providerId", it.getString("id")) }
                val reply = vm.bridge.request("modelProvider/config/write", JSONObject().put("settings", patch))
                val saved = reply.getJSONObject("provider")
                check(preset.ownsProvider(saved) && saved.optString("name") == name.trim()) {
                    "服务商配置回读不匹配"
                }
                val readback = vm.bridge.request("modelProvider/list", JSONObject())
                check(records(readback).any { it.optString("id") == saved.getString("id") && preset.ownsProvider(it) }) {
                    "服务商未进入有效配置"
                }
                configVerified = true
                if (keySaved || preset.id !in vm.bridge.domesticCredentials.value.loaded) restart()
                check(preset.id in vm.bridge.domesticCredentials.value.loaded) {
                    vm.bridge.domesticCredentials.value.failures[preset.id] ?: "本机密钥未进入运行核心"
                }
                after()
                vm.actionFeedback.tryEmit(ActionFeedbackReceipt("服务商配置已回读，密钥已加载；真实模型调用待验证"))
            } catch (failure: Exception) {
                var message = if (configVerified) "配置已保存，授权加载失败，请重新连接或授权"
                    else (failure.message ?: "服务商配置保存或回读失败")
                if (keySaved && !configVerified) {
                    try {
                        withContext(Dispatchers.IO) { DomesticProviderCredentials.restore(context, preset, encryptedBefore) }
                    } catch (_: Exception) {
                        message = "配置回读失败，旧密钥恢复失败；请重新授权并核对服务商配置"
                        withContext(Dispatchers.IO) { vm.bridge.close() }
                    }
                }
                vm.errors.value = vm.errors.value + ("providerWrite" to message)
            } finally {
                encryptedBefore?.fill(0)
                release()
                if (vm.bridge.status.value == EngineStatus.Connected) refresh()
            }
        }
    }

    fun revoke(preset: DomesticModelPreset, after: () -> Unit) {
        if (!acquire()) return
        vm.viewModelScope.launch {
            try {
                // Terminate the process holding the old key before deleting it.
                withContext(Dispatchers.IO) {
                    vm.bridge.close()
                    DomesticProviderCredentials.remove(context, preset)
                }
                vm.connect().join()
                check(vm.bridge.status.value == EngineStatus.Connected) { "密钥已移除；请重新连接核心" }
                check(preset.id !in vm.bridge.domesticCredentials.value.loaded) { "旧密钥仍在核心中，请重新连接" }
                after()
                vm.actionFeedback.tryEmit(ActionFeedbackReceipt("本机密钥已移除，旧运行核心已关闭"))
            } catch (failure: Exception) {
                vm.errors.value = vm.errors.value + ("providerWrite" to (failure.message ?: "移除密钥失败"))
            } finally {
                release()
                if (vm.bridge.status.value == EngineStatus.Connected) refresh()
            }
        }
    }
}
