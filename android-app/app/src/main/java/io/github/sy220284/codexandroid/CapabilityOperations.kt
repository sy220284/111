package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun CodexViewModel.capabilityRequest(
    method: String, params: JSONObject? = JSONObject(), timeout: Long = 45_000,
    sendPermit: (() -> Boolean)? = null, expectedSession: Long? = null
): JSONObject {
    check(bridge.status.value == EngineStatus.Connected) { "请先连接运行引擎" }
    check(!AppDataTransfer.state.value.busy) { "请等待应用数据传输完成" }
    val owner = currentCoroutineContext()[CapabilityRequestOwner]?.owner
        ?: captureTaskOwner() ?: error("运行核心尚未连接")
    check(expectedSession == null || expectedSession == owner.epoch) { "操作所属连接已变化" }
    return owner.guarded({ owner.current(this) }) {
        bridge.request(method, params, timeout, { owner.current(this) && sendPermit?.invoke() != false }, owner.epoch)
    }
}

internal fun JSONObject.value(key: String): String =
    if (isNull(key)) "" else optString(key)

internal fun JSONObject.objects(key: String = "data"): List<JSONObject> {
    val items = optJSONArray(key) ?: return emptyList()
    return (0 until items.length()).mapNotNull(items::optJSONObject)
}

internal suspend fun capabilityPages(
    fetch: suspend (String?) -> JSONObject
): List<JSONObject> {
    var cursor: String? = null
    val budget = CatalogPageBudget()
    val rows = mutableListOf<JSONObject>()
    do {
        budget.beforeRequest()
        val page = fetch(cursor)
        val items = page.objects()
        cursor = page.value("nextCursor").takeIf(String::isNotBlank)
        budget.accept(items.size, cursor)
        rows.addAll(items)
    } while (cursor != null)
    return rows
}

internal fun capabilityPageParams(cursor: String?) = JSONObject().put("limit", 50).apply {
    cursor?.let { put("cursor", it) }
}

internal fun capabilityError(error: Exception): String =
    if (error is TimeoutCancellationException) "运行引擎响应超时，请刷新当前状态"
    else PresentationPolicy.readableError(error.message.orEmpty()).ifBlank { "操作失败，请重试" }

internal class CapabilityOperation(private val vm: CodexViewModel) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
    var receipt by mutableStateOf("")

    fun launch(onRejected: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) { onRejected(); return }
        val owner = vm.captureTaskOwner() ?: run { error = "运行核心尚未连接"; onRejected(); return }
        busy = true
        error = ""
        receipt = ""
        vm.viewModelScope.launch {
            try {
                withContext(CapabilityRequestOwner(owner)) { block() }
            } catch (cancel: CancellationException) {
                if (cancel !is TimeoutCancellationException) throw cancel
                error = capabilityError(cancel)
            } catch (failure: Exception) {
                error = capabilityError(failure)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberCapabilityOperation(vm: CodexViewModel) =
    remember(vm) { CapabilityOperation(vm) }

@Composable
internal fun CapabilityFeedback(operation: CapabilityOperation, progress: String = "正在处理") {
    if (operation.busy) AtlasOperationProgress(progress)
    else {
        if (operation.receipt.isNotBlank()) CodexNotice(operation.receipt, NoticeTone.Success)
        if (operation.error.isNotBlank()) CodexNotice(operation.error, NoticeTone.Error)
    }
}
