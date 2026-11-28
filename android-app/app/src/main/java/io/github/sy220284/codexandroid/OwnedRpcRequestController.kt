package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Owns generic RPC dispatch, mutation admission, response leases, and receipts. */
internal class OwnedRpcRequestController(private val vm: CodexViewModel, private val latestRequests: LatestRequestGate) {
    private val dynamicRequestHistory by lazy { TransientRequestCache(latestRequests, vm.data, vm.errors, vm.loading) }
    private val receiptKeys = mutableSetOf<String>()
    private val pendingMutations = mutableSetOf<String>()

    fun invalidateAccountReceipts() {
        val keys = receiptKeys + vm.data.value.keys.filter {
            it.startsWith("goal:") || it.startsWith("queue:") || it.startsWith("tokenUsage:")
        }
        val reads = keys - pendingMutations
        reads.forEach(latestRequests::cancel)
        vm.data.value = vm.data.value - keys
        vm.errors.value = vm.errors.value - keys
        vm.loading.value = vm.loading.value - reads
        receiptKeys.retainAll(pendingMutations)
    }

    fun request(
        key: String,
        method: String,
        params: JSONObject?,
        onSuccess: ((JSONObject) -> Unit)?,
        userAction: Boolean = false
    ): Unit = with(vm) {
        if (userAction && DataTransferMutationGate.refuse(this, key, method)) return@with
        if (userAction && method in setOf("account/logout", "accountSession/switch") &&
            (loginBusy.value || activeLoginId.value.isNotBlank() ||
                "accountLogout" in loading.value || "accountSessionSwitch" in loading.value)) {
            val reason = "已有账号操作，请完成或取消当前授权后重试"
            errors.value = errors.value + (key to reason)
            viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure(method, reason)) }
            return@with
        }
        if (userAction && AccountVoiceSettingsLease.blocks(method, loading.value)) {
            val reason = "账号声音设置正在写入或读取，请完成后再切换账号"
            errors.value = errors.value + (key to reason)
            viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure(method, reason)) }
            return@with
        }
        if (userAction && method in setOf("thread/queue/add", "thread/queue/update", "thread/queue/delete", "thread/queue/reorder")) {
            val owner = params?.optString("threadId")
            if (owner.isNullOrBlank() || owner != activeThread.value || threadSwitching.value ||
                "queueWrite" in loading.value || steerBusy.value || securitySaveBusy.value ||
                connectionRestarter.busy.value || bridge.status.value != EngineStatus.Connected) {
                val reason = "会话已变化或已有后续指令操作，请完成后重试"
                errors.value = errors.value + (key to reason)
                return@with
            }
        }
        if (userAction && method in setOf("config/value/write", "config/batchWrite", "skills/config/write", "modelProvider/config/write", "modelProvider/default/set") &&
            configBusy()) {
            val reason = "已有配置操作正在处理，请完成后再修改"
            errors.value = errors.value + (key to reason)
            viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure(method, reason)) }
            return@with
        }
        if (method in setOf("thread/delete", "thread/archive") &&
            params?.optString("threadId")?.let { it in threadExecutions.value } == true) {
            val reason = "所选会话仍有执行中的任务，请先停止或等待完成后再整理"
            errors.value = errors.value + (key to reason)
            if (userAction) viewModelScope.launch {
                actionFeedback.emit(ActionFeedbackCopy.failure(method, reason))
            }
            return@with
        }
        if (userAction && ThreadOrganizationPolicy.isOrganization(method) &&
            ("threadLibraryBulk" in loading.value || "threadLibraryAction" in loading.value)) {
            val reason = "已有会话整理操作，请完成后重试"
            errors.value = errors.value + (key to reason)
            viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure(method, reason)) }
            return@with
        }
        val organizationTicket = if (userAction) threadOrganization.begin(method,
            listOfNotNull(params?.optString("threadId")?.takeIf(String::isNotBlank))) else null
        dynamicRequestHistory.retain(key)
        receiptKeys.retainAll(data.value.keys + errors.value.keys + loading.value)
        receiptKeys.add(key)
        if (userAction) pendingMutations.add(key) else pendingMutations.remove(key)
        val ticket = latestRequests.begin(key)
        val configOwner = configGeneration
        val requestEpoch = bridge.connectedSession.value
        val accountOwner = catalogWarmup.accountRevision.value
        val permissionWorkspace = if (key in setOf("composerPermissionConfig", "permissionProfiles")) params?.optString("cwd") else null
        fun ownsConnection() = latestRequests.isLatest(key, ticket) && requestEpoch == bridge.connectedSession.value
        fun ownsReceipt() = ownsConnection() &&
            accountOwner == catalogWarmup.accountRevision.value &&
            (method !in setOf("config/read", "configRequirements/read", "permissionProfile/list") || configOwner == configGeneration) &&
            (permissionWorkspace == null || permissionWorkspace == activeWorkspace.value)
        val retainQueue = method == "thread/queue/list" && key.startsWith("queue:")
        val retainModelCatalog = method == "model/list" && (key == "models" || key.startsWith("composerModels:"))
        if (userAction || (!retainQueue && !retainModelCatalog && !ExtensionCatalogPolicy.retainsSnapshot(key, method)))
            data.value = data.value - key
        errors.value = errors.value - key
        loading.value = loading.value + key
        viewModelScope.launch {
            try {
                val response = dispatchOwnedRequest(method, params, requireNotNull(requestEpoch) { "运行核心尚未连接" },
                    sendPermit = { catalogWarmup.accountRevision.value == accountOwner })
                // Identity-changing commands may emit account/updated before their response.
                // Re-read the new authority instead of publishing an old-identity payload.
                if (userAction && ownsConnection() && !ownsReceipt() &&
                    method in setOf("account/logout", "accountSession/switch")) {
                    refresh("account", "account/read", JSONObject())
                    refresh("accountSessions", "accountSession/list", CatalogRequestContract.accountSessions())
                    actionFeedback.emit(ActionFeedbackCopy.success(method, response))
                    return@launch
                }
                if (ownsReceipt()) {
                    val published = if (key == "apps" && method == "app/list" &&
                        !params?.optString("cursor").isNullOrBlank())
                        ExtensionCatalogPolicy.appendApps(data.value[key], response) else response
                    data.value = data.value + (key to published)
                    errors.value = errors.value - key
                    if (method == "account/read" && key == "account") sessions.accountReady(response)
                    if (userAction && method in setOf("account/logout", "accountSession/switch",
                            "modelProvider/default/set", "modelProvider/config/write")) {
                        accountMetrics.accountChanged()
                        extensions.invalidate()
                        refresh("account", "account/read", JSONObject())
                    }
                    onSuccess?.invoke(response)
                    if (SessionRefreshPolicy.affectsThreads(method)) sessions.refreshChanged(params?.optString("threadId").orEmpty())
                    if (userAction) actionFeedback.emit(
                        ActionFeedbackCopy.success(method, response).copy(
                            undo = threadOrganization.complete(organizationTicket,
                                listOfNotNull(params?.optString("threadId")?.takeIf(String::isNotBlank)))))
                }
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                if (ownsReceipt()) {
                    val reason = failure.message ?: "请求失败"
                    errors.value = errors.value + (key to reason)
                    if (userAction) actionFeedback.emit(
                        ActionFeedbackCopy.failure(method, reason))
                }
            } finally {
                if (latestRequests.complete(key, ticket)) {
                    pendingMutations.remove(key)
                    loading.value = loading.value - key
                }
            }
        }
        Unit
    }
}
