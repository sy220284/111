package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountMetricsSyncTest {
    @Test fun prefetchesBothAndKeepsCachedResultsDuringRefreshAndFailure() = runBlocking {
        val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
        val loading = MutableStateFlow<Set<String>>(emptySet())
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        var time = 0L
        val calls = mutableListOf<String>()
        var next = CompletableDeferred<Unit>()
        var fail = false
        val sync = AccountMetricsSync(this, data, loading, errors, { true }, { time }) { key ->
            calls += key
            next.await()
            if (fail) error("读取失败")
            JSONObject().put("value", key)
        }
        sync.accountChanged()
        yield()
        assertEquals(setOf("usage", "limits"), loading.value)
        next.complete(Unit)
        yield()
        assertEquals(setOf("usage", "limits"), data.value.keys)
        assertTrue(loading.value.isEmpty())
        sync.refreshIfStale()
        yield()
        assertEquals(2, calls.size)
        time = 120_000
        next = CompletableDeferred()
        fail = true
        sync.refreshIfStale()
        yield()
        assertEquals(setOf("usage", "limits"), data.value.keys)
        next.complete(Unit)
        yield()
        assertEquals(setOf("usage", "limits"), errors.value.keys)
        assertEquals(setOf("usage", "limits"), data.value.keys)
    }

    @Test fun notificationsCoalesceAndAccountChangeDiscardsOldResults() = runBlocking {
        val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
        val loading = MutableStateFlow<Set<String>>(emptySet())
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        var connected = true
        var calls = 0
        var pending = CompletableDeferred<Unit>()
        val sync = AccountMetricsSync(this, data, loading, errors, { connected }, { 0L }) {
            val version = ++calls
            pending.await()
            JSONObject().put("version", version)
        }
        sync.refresh(setOf("limits"))
        yield()
        repeat(10) { sync.refresh(setOf("limits")) }
        pending.complete(Unit)
        yield()
        assertEquals(2, calls)
        assertEquals(2, data.value["limits"]?.optInt("version"))
        pending = CompletableDeferred()
        sync.refresh(setOf("limits"))
        yield()
        connected = false
        sync.accountChanged()
        pending.complete(Unit)
        yield()
        assertTrue(data.value.isEmpty())
        assertTrue(loading.value.isEmpty())
        connected = true
        sync.refreshIfStale()
        yield()
        assertEquals(setOf("usage", "limits"), data.value.keys)
        assertEquals(5, calls)
    }
}
