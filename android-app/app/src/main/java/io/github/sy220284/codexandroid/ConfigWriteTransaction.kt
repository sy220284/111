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
        check(isCurrent()) { "配置所属账号或核心已变化，请重新读取" }
        val receipt = write()
        check(isCurrent()) { "配置所属账号或核心已变化，请重新读取" }
        val effective = read()
        check(isCurrent()) { "配置所属账号或核心已变化，请重新读取" }
        effective.getJSONObject("config")
        publish(effective)
        verify(receipt, effective)
        return effective
    }
}
