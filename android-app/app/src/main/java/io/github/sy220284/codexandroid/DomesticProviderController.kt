package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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

    private suspend fun closeIdleCore() {
        check(vm.bridge.reserveRestart()) { "内核仍有请求或审批待处理，请稍后重试" }
        try {
            withContext(Dispatchers.IO) {
                vm.bridge.closeForRestart {
                    ConnectionRestartPolicy.canContinueOwnedRestart(vm.globalWorkSnapshot())
                }
            }
        } finally {
            vm.bridge.releaseRestart()
        }
    }

    private suspend fun restart() {
        closeIdleCore()
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

    fun configure(preset: DomesticModelPreset, name: String, endpoint: String, key: String, after: () -> Unit) {
        if (name.isBlank() || !preset.matchesEndpoint(endpoint) || (preset.needsWorkspace && preset.workspaceId(endpoint) == null) || (key.isNotBlank() && !DomesticProviderCredentials.valid(key))) {
            vm.errors.value = vm.errors.value + ("providerWrite" to "服务商名称或密钥格式无效")
            return
        }
        val owner = vm.captureTaskOwner() ?: return
        if (!acquire()) return
        vm.viewModelScope.launch {
            var keySaved = false
            var configVerified = false
            var writeAttempted = false
            try {
                val requestedName = name.trim()
                val requestedEndpoint = endpoint.trim().trimEnd('/')
                val current = owner.request(vm, "modelProvider/list", JSONObject())
                val matching = records(current).filter { preset.ownsProvider(it) }
                check(matching.size <= 1) { "存在重复的预置服务商配置，请先整理" }
                val existing = matching.singleOrNull()
                check(existing == null || existing.optBoolean("canEdit")) { "当前预置服务商配置只读" }
                if (key.isBlank()) {
                    check(withContext(Dispatchers.IO) {
                        DomesticProviderCredentials.matchesStoredEndpoint(context, preset, requestedEndpoint)
                    }) { "业务空间或服务地址已变更，请填写该地址的 API Key" }
                }
                val patch = JSONObject().put("type", "customGateway")
                    .put("name", requestedName).put("baseUrl", requestedEndpoint).put("envKey", preset.envKey)
                existing?.let { patch.put("providerId", it.getString("id")) }
                fun matches(value: JSONObject) = preset.ownsProvider(value) &&
                    value.optString("baseUrl").trimEnd('/') == requestedEndpoint &&
                    value.optString("name") == requestedName
                // Confirm the native configuration before replacing the active key.
                // An uncertain write must never roll back a key against a possibly new endpoint.
                ConfigWriteTransaction.executeSnapshot(
                    write = {
                        writeAttempted = true
                        owner.request(vm, "modelProvider/config/write", JSONObject().put("settings", patch))
                    },
                    read = { owner.request(vm, "modelProvider/list", JSONObject()) },
                    isCurrent = { owner.current(vm) },
                    publish = {},
                    verify = { reply, readback ->
                        val saved = reply.getJSONObject("provider")
                        check(matches(saved) && records(readback).any { it.optString("id") == saved.getString("id") && matches(it) }) {
                            "服务商配置与原生回读结果不一致"
                        }
                    })
                configVerified = true
                if (key.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        owner.requireCurrent(vm)
                        DomesticProviderCredentials.save(context, preset, key, requestedEndpoint)
                        keySaved = true
                    }
                }
                owner.requireCurrent(vm)
                if (keySaved || preset.id !in vm.bridge.domesticCredentials.value.loaded) restart()
                check(owner.account == vm.catalogWarmup.accountRevision.value) { "账号已切换，请核对服务商配置" }
                check(preset.id in vm.bridge.domesticCredentials.value.loaded) {
                    vm.bridge.domesticCredentials.value.failures[preset.id] ?: "本机密钥未进入运行核心"
                }
                after()
                vm.actionFeedback.tryEmit(ActionFeedbackReceipt("服务商配置已回读，密钥已加载；真实模型调用待验证"))
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val detail = failure.message ?: "操作失败"
                vm.errors.value = vm.errors.value + ("providerWrite" to when {
                    configVerified && keySaved -> "配置和密钥已保存，加载未确认；请重新连接并核对授权：$detail"
                    configVerified -> "配置已保存，密钥未更新或加载未确认；请重新授权：$detail"
                    writeAttempted -> "配置写入未确认，原密钥保持不变；请刷新配置后重试：$detail"
                    else -> detail
                })
            } finally {
                release()
                if (vm.bridge.status.value == EngineStatus.Connected) refresh()
            }
        }
    }

    fun revoke(preset: DomesticModelPreset, after: () -> Unit) {
        val owner = vm.captureTaskOwner() ?: return
        if (!acquire()) return
        vm.viewModelScope.launch {
            try {
                // Terminate the process holding the old key before deleting it.
                owner.requireCurrent(vm)
                closeIdleCore()
                withContext(Dispatchers.IO) {
                    DomesticProviderCredentials.remove(context, preset)
                }
                vm.connect().join()
                check(vm.bridge.status.value == EngineStatus.Connected) { "密钥已移除；请重新连接核心" }
                check(owner.account == vm.catalogWarmup.accountRevision.value) { "账号已切换，请核对授权" }
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
