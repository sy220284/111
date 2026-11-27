package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SessionSearchHistory(val title: List<String> = emptyList(),
    val full: List<String> = emptyList(), val error: String = "")

/** Process-owned, serialized persistence. Leaving the drawer cannot interrupt
 * an accepted history write or let a later clear race with an earlier record. */
internal class SessionSearchHistoryController(private val context: Context, private val scope: CoroutineScope) {
    private val lock = Mutex()
    val state = MutableStateFlow(SessionSearchHistory())
    init {
        run {
            val title = RecentThreadSearches.load(context, false)
            val full = RecentThreadSearches.load(context, true)
            SessionSearchHistory(title, full)
        }
    }
    fun record(fullText: Boolean, query: String) = run {
        val next = RecentThreadSearches.record(context, fullText, query)
        if (fullText) state.value.copy(full = next, error = "") else state.value.copy(title = next, error = "")
    }
    fun clear(fullText: Boolean) = run {
        val next = RecentThreadSearches.clear(context, fullText)
        if (fullText) state.value.copy(full = next, error = "") else state.value.copy(title = next, error = "")
    }
    private fun run(operation: () -> SessionSearchHistory) {
        scope.launch {
            lock.withLock {
                val result = codexLoadResult { withContext(Dispatchers.IO) { operation() } }
                state.value = result.getOrElse { state.value.copy(error = it.message ?: "搜索记录读写失败") }
            }
        }
    }
}
