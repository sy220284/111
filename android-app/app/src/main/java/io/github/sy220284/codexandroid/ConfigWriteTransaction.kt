package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Shared write/read/verify transaction. Publish effective values even when an
 * override rejects the requested value; never publish a receipt for a retired identity. */
internal object ConfigWriteTransaction {
    suspend fun execute(
        write: suspend () -> JSONObject,
        read: suspend () -> JSONObject,
        isCurrent: () -> Boolean = { true },
        publish: (JSONObject) -> Unit = {},
        verify: (JSONObject, JSONObject) -> Unit
    ): JSONObject {
        return executeSnapshot(write, read, isCurrent, { effective ->
            effective.getJSONObject("config")
            publish(effective)
        }, verify)
    }

    /** Shared identity-aware transaction for both raw config and typed catalog snapshots. */
    suspend fun <T> executeSnapshot(
        write: suspend () -> JSONObject,
        read: suspend () -> T,
        isCurrent: () -> Boolean,
        publish: (T) -> Unit,
        verify: (JSONObject, T) -> Unit
    ): T {
        check(isCurrent()) { "配置所属账号或核心已变化，请重新读取" }
        val receipt = write()
        check(isCurrent()) { "配置所属账号或核心已变化，请重新读取" }
        val effective = read()
        check(isCurrent()) { "配置所属账号或核心已变化，请重新读取" }
        publish(effective)
        verify(receipt, effective)
        return effective
    }
}
