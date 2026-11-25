package io.github.sy220284.codexandroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class FeatureSwitchSnapshot(
    val session: Long,
    val account: Long,
    val rows: List<CoreFeatureSwitch>,
    val managed: Set<String>,
    val userVersion: String?,
    val userFeatures: JSONObject?
)

/** Complete paginated authoritative list. Optional thread scope is read-only. */
internal suspend fun readCoreFeatureSwitches(
    vm: CodexViewModel, session: Long, threadId: String? = null,
    account: Long = vm.catalogWarmup.accountRevision.value
): List<CoreFeatureSwitch> {
    val rows = mutableListOf<CoreFeatureSwitch>()
    val keys = mutableSetOf<String>()
    val budget = CatalogPageBudget()
    var cursor: String? = null
    do {
        budget.beforeRequest()
        val page = vm.capabilityRequest("experimentalFeature/list",
            capabilityPageParams(cursor).apply { threadId?.let { put("threadId", it) } },
            sendPermit = { vm.catalogWarmup.accountRevision.value == account }, expectedSession = session)
        val data = page.getJSONArray("data")
        cursor = page.value("nextCursor").takeIf { it.isNotBlank() }
        budget.accept(data.length(), cursor)
        for (i in 0 until data.length()) {
            val row = FeatureSwitchPolicy.parse(data.getJSONObject(i))
            check(keys.add(row.name)) { "内核功能目录返回重复项" }
            rows.add(row)
        }
        // A complete snapshot is published only after the terminal cursor.
    } while (cursor != null)
    check(rows.isNotEmpty()) { "内核未返回功能目录" }
    return rows
}

/** One process-owned transaction; leaving the page does not abandon a write. */
internal class FeatureSwitchController(private val vm: CodexViewModel) {
    var snapshot by mutableStateOf<FeatureSwitchSnapshot?>(null)
        private set
    var attemptedSession by mutableStateOf<Long?>(null)
        private set
    var attemptedAccount by mutableStateOf<Long?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var receipt by mutableStateOf("")
        private set
    private val key = "featureSwitchTransaction"

    private fun current(session: Long, account: Long): Boolean =
        vm.bridge.connectedSession.value == session && vm.catalogWarmup.accountRevision.value == account

    private suspend fun read(session: Long, account: Long): FeatureSwitchSnapshot {
        check(current(session, account)) { "配置所属账号或核心已变化，请重新读取" }
        val rows = readCoreFeatureSwitches(vm, session, account = account)
        val requirements = vm.capabilityRequest("configRequirements/read", sendPermit = { current(session, account) }, expectedSession = session)
            .optJSONObject("requirements")?.optJSONObject("featureRequirements")
        val managed = requirements?.keys()?.asSequence()?.toSet().orEmpty()
        val config = vm.capabilityRequest("config/read", JSONObject().put("includeLayers", true),
            sendPermit = { current(session, account) }, expectedSession = session)
        val user = config.objects("layers").firstOrNull {
            it.optJSONObject("name")?.let { name ->
                name.optString("type") == "user" && name.value("profile").isBlank()
            } == true
        }
        check(current(session, account)) { "配置所属账号或核心已变化，请重新读取" }
        return FeatureSwitchSnapshot(session, account, rows, managed, user?.value("version")?.takeIf { it.isNotBlank() },
            user?.optJSONObject("config")?.optJSONObject("features"))
    }

    fun refresh() {
        val session = vm.bridge.connectedSession.value ?: return
        if (busy) return
        val account = vm.catalogWarmup.accountRevision.value
        attemptedSession = session
        attemptedAccount = account
        busy = true
        error = ""
        receipt = ""
        vm.loading.value = vm.loading.value + key
        vm.viewModelScope.launch {
            try {
                snapshot = read(session, account)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                snapshot = null
                error = capabilityError(e)
            } catch (e: Exception) {
                snapshot = null
                error = capabilityError(e)
            } finally {
                busy = false
                vm.loading.value = vm.loading.value - key
            }
        }
    }

    fun change(row: CoreFeatureSwitch, enabled: Boolean, expectedSession: Long, expectedAccount: Long) {
        val session = vm.bridge.connectedSession.value ?: return
        val account = vm.catalogWarmup.accountRevision.value
        if (busy || expectedSession != session || expectedAccount != account ||
            snapshot?.session != session || snapshot?.account != account) return
        attemptedSession = session
        attemptedAccount = account
        error = ""
        receipt = ""
        if (vm.configBusy()) { error = "已有配置操作正在处理，请稍后重试"; return }
        if (DataTransferMutationGate.refuse(vm, key, "config/batchWrite")) {
            error = "本机数据传输期间不能修改设置"
            return
        }
        busy = true
        vm.configTransactionBusy.value = true
        vm.loading.value = vm.loading.value + key
        vm.viewModelScope.launch {
            var writeAttempted = false
            try {
                val before = read(session, account)
                snapshot = before
                val actual = before.rows.single { it.name == row.name }
                check(actual.restriction(before.managed) == null) {
                    actual.restriction(before.managed).orEmpty()
                }
                check(actual.enabled == row.enabled) { "功能状态已变化，请根据最新状态重新操作" }
                writeAttempted = true
                ConfigWriteTransaction.executeSnapshot(
                    write = { vm.capabilityRequest("config/batchWrite",
                        FeatureSwitchPolicy.writeParams(row.name, enabled, before.userVersion),
                        sendPermit = { current(session, account) }, expectedSession = session) },
                    read = { read(session, account) },
                    isCurrent = { current(session, account) },
                    publish = { snapshot = it },
                    verify = { write, after ->
                        val applied = FeatureSwitchPolicy.verifyWrite(write,
                            FeatureSwitchPolicy.persistedEnabled(after.userFeatures, row.name),
                            after.rows.single { it.name == row.name }.enabled, enabled)
                        if (applied) receipt = "${row.title}已${if (enabled) "开启" else "关闭"} · 内核已回读"
                        else error = "用户配置已保存，但实际生效值受到其他配置层或会话状态影响。" +
                            write.optJSONObject("overriddenMetadata")?.optString("message").orEmpty()
                    })
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                snapshot = null
                error = capabilityError(e)
            } catch (e: Exception) {
                // If a write may have reached the server, read again before permitting another mutation.
                error = capabilityError(e)
                if (writeAttempted) {
                    snapshot = null
                    try { snapshot = read(session, account) }
                    catch (cancel: CancellationException) {
                        if (cancel !is TimeoutCancellationException) throw cancel
                        error += "；重新读取超时，请刷新"
                    }
                    catch (refresh: Exception) { error += "；重新读取失败：" + capabilityError(refresh) }
                }
            } finally {
                vm.configTransactionBusy.value = false
                vm.loading.value = vm.loading.value - key
                busy = false
            }
            if (writeAttempted && current(session, account)) {
                vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
                vm.refreshComposerPermissions()
            }
            if (current(session, account)) {
                if (receipt.isNotBlank()) vm.actionFeedback.emit(ActionFeedbackReceipt(receipt))
                else if (error.isNotBlank()) vm.actionFeedback.emit(ActionFeedbackReceipt(error, isError = true))
            }
        }
    }
}
