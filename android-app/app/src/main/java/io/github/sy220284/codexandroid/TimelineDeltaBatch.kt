package io.github.sy220284.codexandroid

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import org.json.JSONObject

/** Adjacent native deltas are appended in bounded chunks before materializing a snapshot.
 * A different thread, turn, item, summary segment or lifecycle event ends the batch. */
internal class TimelineDeltaBatch(private val first: JSONObject) {
    private val method = first.optString("method")
    private val params = first.getJSONObject("params")
    private val text = StringBuilder(params.optString("delta"))
    private val key = identity(params)

    private fun identity(value: JSONObject) = listOf(value.optString("threadId"),
        value.optString("turnId"), value.optString("itemId"), value.optString("summaryIndex"))
    fun append(next: JSONObject): Boolean {
        val incoming = next.optJSONObject("params") ?: return false
        if (next.optString("method") != method || identity(incoming) != key ||
            text.length + incoming.optString("delta").length > 65536) return false
        text.append(incoming.optString("delta"))
        return true
    }
    fun notification(): JSONObject = JSONObject(first.toString()).put("params",
        JSONObject(params.toString()).put("delta", text.toString()))
    companion object {
        private val methods = setOf("item/agentMessage/delta", "item/plan/delta",
            "item/reasoning/summaryTextDelta", "item/commandExecution/outputDelta", "item/fileChange/outputDelta")
        fun accepts(message: JSONObject): Boolean {
            val params = message.optJSONObject("params") ?: return false
            return message.optString("method") in methods &&
                params.optString("threadId").isNotBlank() && params.optString("turnId").isNotBlank() &&
                params.optString("itemId").isNotBlank() && params.opt("delta") is String
        }
    }
}

/** Serial dispatch preserves lifecycle ordering. Channel capacity and collection time are bounded. */
@OptIn(FlowPreview::class)
internal suspend fun collectTimelineNotifications(events: Flow<JSONObject>, dispatch: suspend (JSONObject) -> Unit) = coroutineScope {
    val channel = events.produceIn(this)
    var pending: JSONObject? = null
    while (true) {
        val first = pending ?: channel.receiveCatching().let { result ->
            if (result.isClosed) { result.exceptionOrNull()?.let { throw it }; return@coroutineScope }
            result.getOrThrow()
        }
        pending = null
        if (!TimelineDeltaBatch.accepts(first)) { dispatch(first); continue }
        val batch = TimelineDeltaBatch(first)
        delay(8)
        for (i in 0 until 63) {
            val next = channel.tryReceive().getOrNull() ?: break
            if (!TimelineDeltaBatch.accepts(next) || !batch.append(next)) { pending = next; break }
        }
        dispatch(batch.notification())
    }
}
