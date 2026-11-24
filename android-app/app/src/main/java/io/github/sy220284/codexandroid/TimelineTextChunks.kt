package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Persistent chunks share their previous nodes, without copying accumulated text on append.
 * Only a reader requesting the complete value materializes that snapshot. */
internal class TimelineTextChunks private constructor(private val last: Node?, val length: Int) {
    private class Node(val previous: Node?, val value: String)
    fun append(value: String): TimelineTextChunks =
        if (value.isEmpty()) this else TimelineTextChunks(Node(last, value), length + value.length)

    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) { tail(length) }

    fun tail(limit: Int): String {
        var remaining = limit.coerceIn(0, length)
        val parts = ArrayDeque<String>()
        var cursor = last
        while (cursor != null && remaining > 0) {
            val part = cursor.value.takeLast(remaining)
            parts.addFirst(part)
            remaining -= part.length
            cursor = cursor.previous
        }
        return parts.joinToString("")
    }
    companion object {
        fun from(text: String) = TimelineTextChunks(null, 0).append(text)
    }
}

/** One native item's streaming fields. The original metadata is parsed once; snapshots
 * never modify it, and evidence JSON is materialized only when explicitly read. */
internal class TimelineNativeStream private constructor(
    private val base: String, val metadata: JSONObject, private val label: String,
    val field: String, private val parts: List<TimelineTextChunks>
) {
    fun append(delta: String, index: Int): TimelineNativeStream {
        require(index >= 0) { "无效的消息分段索引" }
        val next = parts.toMutableList()
        while (next.size <= index) next.add(TimelineTextChunks.from(""))
        next[index] = next[index].append(delta)
        return TimelineNativeStream(base, metadata, label, field, next)
    }
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        when (field) {
            "text" -> parts.first().text
            "summary" -> parts.map { it.text }.filter(String::isNotBlank).joinToString("\n\n")
            else -> label
        }
    }
    val evidence: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        JSONObject(base).apply {
            put(field, if (field == "summary") JSONArray(parts.map { it.text }) else parts.first().text)
        }.toString()
    }
    fun outputTail(limit: Int) = parts.firstOrNull()?.tail(limit).orEmpty()
    fun definitelyDifferent(other: TimelineNativeStream): Boolean =
        field == other.field && base == other.base &&
            (parts.size != other.parts.size || parts.indices.any { parts[it].length != other.parts[it].length })

    companion object {
        fun from(item: TimelineItem, field: String): TimelineNativeStream {
            val base = item.evidence
            val metadata = JSONObject(base)
            val parts = if (field == "summary") {
                val values = metadata.optJSONArray(field) ?: JSONArray()
                (0 until values.length()).map { TimelineTextChunks.from(values.optString(it)) }
            } else listOf(TimelineTextChunks.from(if (metadata.isNull(field)) "" else metadata.optString(field)))
            return TimelineNativeStream(base, metadata, if (field in setOf("text", "summary")) "" else item.text, field, parts)
        }
    }
}
