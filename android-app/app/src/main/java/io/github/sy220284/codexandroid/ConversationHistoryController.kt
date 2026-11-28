package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Native history paging is bound to the active conversation generation. */
internal fun readHistoryPage(payload: JSONObject): List<TimelineItem> {
    val rows = payload.getJSONArray("data")
    return (0 until rows.length()).map { TimelineEventReducer.historyEntry(rows.getJSONObject(it)) }.reversed()
}
internal fun historyCursor(payload: JSONObject): String? = if (payload.isNull("nextCursor")) null else payload.optString("nextCursor").takeIf(String::isNotBlank)
internal fun CodexViewModel.loadEarlierHistory() {
    val id = activeThread.value ?: return
    if (loadingEarlier.value || threadSwitching.value || timelineCompacting) return
    val epoch = timelineEpoch
    val owner = captureTaskOwner() ?: run {
        errors.value = errors.value + ("timeline" to "运行核心尚未连接，请重新连接后读取")
        return
    }
    fun current() = owner.current(this) && activeThread.value == id && timelineEpoch == epoch && !threadSwitching.value
    loadingEarlier.value = true
    viewModelScope.launch {
        try {
            var older = timelineWindow.readEarlier(id, epoch)
            if (older.isEmpty() && current()) {
                val cursor = olderServerCursor.value ?: return@launch
                val response = owner.request(this@loadEarlierHistory, "thread/items/list", JSONObject().put("threadId", id)
                    .put("sortDirection", "desc").put("limit", 120).put("cursor", cursor))
                if (!current()) return@launch
                val reader = historyTurnOutcomes?.takeIf { it.threadId == id } ?: HistoryTurnOutcomes(id) { method, params ->
                    owner.request(this@loadEarlierHistory, method, params)
                }.also { historyTurnOutcomes = it }
                older = reader.reconcile(readHistoryPage(response))
                if (!current()) return@launch
                olderServerCursor.value = historyCursor(response)
            }
            if (current() && older.isNotEmpty()) {
                val known = timeline.value.mapTo(hashSetOf()) { it.id }
                timeline.value = older.filterNot { it.id in known } + timeline.value
                errors.value = errors.value - "timeline"
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            if (current()) errors.value = errors.value + ("timeline" to
                (failure.message ?: "无法加载更早的记录"))
        } finally { loadingEarlier.value = false }
    }
}
