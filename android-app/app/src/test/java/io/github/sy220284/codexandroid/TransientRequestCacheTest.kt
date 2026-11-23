package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TransientRequestCacheTest {
    @Test fun evictsAllFacetsAndInvalidatesStaleResponsesTogether() {
        val gate = LatestRequestGate()
        val data = MutableStateFlow(mapOf("library:0" to JSONObject()))
        val errors = MutableStateFlow(mapOf("library:0" to "上一轮错误"))
        val loading = MutableStateFlow(setOf("library:0"))
        val cache = TransientRequestCache(gate, data, errors, loading, capacity = 2)
        val ticket = gate.begin("library:0")
        cache.retain("library:0")
        cache.retain("library:1")
        cache.retain("fullSearch:third")
        assertFalse(gate.isLatest("library:0", ticket))
        assertFalse(gate.complete("library:0", ticket))
        assertFalse(data.value.containsKey("library:0"))
        assertFalse(errors.value.containsKey("library:0"))
        assertFalse(loading.value.contains("library:0"))
    }

    @Test fun revisitRefreshesRecencyWithoutInvalidatingItsOwnRequest() {
        val gate = LatestRequestGate()
        val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        val loading = MutableStateFlow<Set<String>>(emptySet())
        val cache = TransientRequestCache(gate, data, errors, loading, capacity = 2)
        val ticket = gate.begin("library:first")
        cache.retain("library:first")
        cache.retain("library:second")
        cache.retain("library:first")
        cache.retain("library:third")
        assertTrue(gate.isLatest("library:first", ticket))
    }

    @Test fun persistentRequestKeysNeverEnterTheTransientEvictionQueue() {
        val gate = LatestRequestGate()
        val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        val loading = MutableStateFlow<Set<String>>(emptySet())
        val cache = TransientRequestCache(gate, data, errors, loading, capacity = 1)
        val permanent = gate.begin("account")
        val first = gate.begin("library:first")
        cache.retain("account")
        cache.retain("library:first")
        cache.retain("library:second")
        assertTrue(gate.isLatest("account", permanent))
        assertFalse(gate.isLatest("library:first", first))
    }

    @Test fun requiresPositiveCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            TransientRequestCache(
                LatestRequestGate(),
                MutableStateFlow<Map<String, JSONObject>>(emptyMap()),
                MutableStateFlow<Map<String, String>>(emptyMap()),
                MutableStateFlow<Set<String>>(emptySet()),
                capacity = 0
            )
        }
    }
}
