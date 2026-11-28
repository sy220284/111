package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Persistent chunks share their previous nodes, without copying accumulated text on append.
 * Only a reader requesting the complete value materializes that snapshot. */
internal class TimelineTextChunks private constructor(private val last: Node?, val length: Int, val hasNonWhitespace: Boolean) {
    private class Node(val previous: Node?, val value: String)
    fun append(value: String): TimelineTextChunks =
        if (value.isEmpty()) this else TimelineTextChunks(Node(last, value), length + value.length,
            hasNonWhitespace || value.isNotBlank())

    /** Return only the shared-chain append, or null for a replacement snapshot. */
    fun suffixSince(previous: TimelineTextChunks): List<String>? {
        if (previous.length > length) return null
        val added = ArrayList<String>()
        var cursor = last
        while (cursor !== previous.last) {
            if (cursor == null) return null
            added.add(cursor.value)
            cursor = cursor.previous
        }
        return added.asReversed()
    }

    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) { tail(length) }

    fun tail(limit: Int): String {
        var remaining = limit.coerceIn(0, length)
        val parts = java.util.ArrayDeque<String>()
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
        fun from(text: String) = TimelineTextChunks(null, 0, false).append(text)
    }
}

/** One native item's streaming fields. The original metadata is parsed once; snapshots
 * never modify it, and evidence JSON is materialized only when explicitly read. */
internal class TimelineNativeStream private constructor(
    private val base: String, val metadata: JSONObject, private val label: String,
    val field: String, private val parts: PersistentIndex<Long, TimelineTextChunks>,
    private val partCount: Int, private val totalLength: Long
) {
    fun append(delta: String, index: Long): TimelineNativeStream {
        if (index < 0 || (field != "summary" && index != 0L))
            throw InvalidNativeSummaryIndex(index)
        val previous = parts[index]
        val next = (previous ?: TimelineTextChunks.from("")).append(delta)
        // The protocol index is i64. Store only received segments, never
        // manufacture a list padded to an index supplied by the provider.
        return TimelineNativeStream(base, metadata, label, field, parts.put(index, next),
            partCount + if (previous == null) 1 else 0, totalLength + delta.length)
    }
    private fun orderedParts(): Sequence<TimelineTextChunks> = parts.values().asSequence()
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        when (field) {
            "text" -> parts[0L]?.text.orEmpty()
            "summary" -> orderedParts().map { it.text }.filter(String::isNotBlank).joinToString("\n\n")
            else -> label
        }
    }
    val evidence: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        JSONObject(base).apply {
            // Streaming evidence contains observed segments in native index order;
            // completed items replace it with the authoritative native payload.
            put(field, if (field == "summary") JSONArray(orderedParts().map { it.text }.toList())
                else parts[0L]?.text.orEmpty())
        }.toString()
    }
    fun displayChunks(): TimelineTextChunks? = if (field == "text") parts[0L] else null
    fun outputTail(limit: Int) = parts[0L]?.tail(limit).orEmpty()
    fun definitelyDifferent(other: TimelineNativeStream): Boolean =
        field == other.field && base == other.base &&
            (partCount != other.partCount || totalLength != other.totalLength)

    companion object {
        fun from(item: TimelineItem, field: String): TimelineNativeStream {
            val base = item.evidence
            val metadata = JSONObject(base)
            var parts = PersistentIndex<Long, TimelineTextChunks>()
            var count = 0
            var length = 0L
            fun add(index: Long, text: String) {
                parts = parts.put(index, TimelineTextChunks.from(text))
                count++
                length += text.length
            }
            if (field == "summary") {
                val values = metadata.optJSONArray(field) ?: JSONArray()
                for (index in 0 until values.length()) add(index.toLong(), values.optString(index))
            } else add(0L, if (metadata.isNull(field)) "" else metadata.optString(field))
            return TimelineNativeStream(base, metadata, if (field in setOf("text", "summary")) "" else item.text,
                field, parts, count, length)
        }
    }
}

internal class InvalidNativeSummaryIndex(index: Any) :
    IllegalArgumentException("无效的原生消息分段索引：$index")
