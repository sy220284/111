package io.github.sy220284.codexandroid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Process-owned, read-only account snapshots. All calls and flow writes run on the owner's main scope. */
internal class AccountMetricsSync(
    private val scope: CoroutineScope,
    private val data: MutableStateFlow<Map<String, JSONObject>>,
    private val loading: MutableStateFlow<Set<String>>,
    private val errors: MutableStateFlow<Map<String, String>>,
    private val canRead: () -> Boolean,
    private val clock: () -> Long,
    private val read: suspend (String) -> JSONObject
) {
    private val keys = setOf("usage", "limits")
    private val jobs = mutableMapOf<String, Job>()
    private val dirty = mutableSetOf<String>()
    private val lastAttempt = mutableMapOf<String, Long>()
    private var generation = 0L
    private var foregroundJob: Job? = null

    fun accountChanged() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        dirty.clear()
        lastAttempt.clear()
        data.value = data.value - keys
        loading.value = loading.value - keys
        errors.value = errors.value - keys
        refresh()
    }

    /** Foreground app lifetime, independent of which page is visible. No idle background keepalive. */
    fun foreground(active: Boolean) {
        if (!active) {
            foregroundJob?.cancel()
            foregroundJob = null
            return
        }
        refreshIfStale()
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            while (isActive) {
                delay(120_000)
                refreshIfStale()
            }
        }
    }

    fun refreshIfStale() {
        val now = clock()
        refresh(keys.filterTo(mutableSetOf()) { key ->
            key !in jobs && (lastAttempt[key]?.let { now - it >= 120_000 } ?: true)
        })
    }

    /** Concurrent notifications coalesce into at most one follow-up read per in-flight key. */
    fun refresh(requested: Set<String> = keys) {
        if (!canRead()) return
        for (key in requested) {
            require(key in keys) { "未知账号统计类型：$key" }
            dirty.add(key)
            if (key in jobs) continue
            val epoch = generation
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (epoch == generation && dirty.remove(key) && canRead()) {
                        lastAttempt[key] = clock()
                        loading.value = loading.value + key
                        try {
                            val result = read(key)
                            if (epoch == generation) {
                                data.value = data.value + (key to result)
                                errors.value = errors.value - key
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            if (epoch == generation) errors.value =
                                errors.value + (key to "账号数据读取超时")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (epoch == generation) errors.value =
                                errors.value + (key to (failure.message ?: "账号数据读取失败"))
                        }
                    }
                } finally {
                    if (epoch == generation) {
                        jobs.remove(key)
                        loading.value = loading.value - key
                    }
                }
            }
            jobs[key] = job
            job.start()
        }
    }
}
