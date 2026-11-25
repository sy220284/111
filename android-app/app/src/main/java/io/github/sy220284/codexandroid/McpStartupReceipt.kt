package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Measured startup attempts, scoped by the native connection, thread and registration. */
internal data class McpStartupReceipt(val epoch: Long, val thread: String?, val environment: String?,
    val name: String, val status: String, val durationMs: Long?, val reason: String?) {
    fun summary(): String = listOfNotNull(PresentationPolicy.serviceState(status),
        durationMs?.let { "启动耗时 ${it} 毫秒" }).joinToString(" · ")

    companion object {
        fun optional(json: JSONObject, key: String): String? =
            (json.opt(key) as? String)?.takeIf { it.isNotBlank() }
        fun parse(epoch: Long, json: JSONObject): McpStartupReceipt? {
            val name = optional(json, "name") ?: return null
            val status = optional(json, "status") ?: return null
            if (epoch < 0 || status !in setOf("starting", "ready", "failed", "cancelled")) return null
            val duration = (json.opt("durationMs") as? Number)?.toLong()?.takeIf { it >= 0 }
            val reason = if (json.optString("failureReason") == "reauthenticationRequired") "需要重新授权"
                else optional(json, "error")?.let { PresentationPolicy.readableError(it).take(400) }
            return McpStartupReceipt(epoch, optional(json, "threadId"), optional(json, "environmentId"),
                name, status, duration, reason)
        }
        fun merge(old: List<McpStartupReceipt>, next: McpStartupReceipt): List<McpStartupReceipt> =
            (old.filterNot { it.epoch != next.epoch ||
                (it.thread == next.thread && it.environment == next.environment && it.name == next.name) } + next).takeLast(128)
    }
}
