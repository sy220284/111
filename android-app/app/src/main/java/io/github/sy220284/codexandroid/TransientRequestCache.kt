package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Bounds transient library / full-text query results and their request ownership.
 *
 * A stale response that finishes after eviction must not populate a newer UI
 * state. The authoritative history remains in Codex; this only owns view caches.
 */
internal class TransientRequestCache(
    private val gate: LatestRequestGate,
    private val data: MutableStateFlow<Map<String, JSONObject>>,
    private val errors: MutableStateFlow<Map<String, String>>,
    private val loading: MutableStateFlow<Set<String>>,
    private val capacity: Int = 32
) {
    init { require(capacity > 0) { "临时查询缓存容量必须大于零" } }

    private val recent = ArrayDeque<String>()

    fun retain(key: String) {
        if (!key.startsWith("library:") && !key.startsWith("fullSearch:")) return
        recent.remove(key)
        recent.addLast(key)
        while (recent.size > capacity) {
            val expired = recent.removeFirst()
            gate.cancel(expired)
            data.value = data.value - expired
            errors.value = errors.value - expired
            loading.value = loading.value - expired
        }
    }
}
