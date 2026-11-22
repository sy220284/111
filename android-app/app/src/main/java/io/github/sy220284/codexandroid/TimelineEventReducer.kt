package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Centralizes live item updates and authoritative ID reconciliation. */
internal object TimelineEventReducer {
    fun reduce(method: String, params: JSONObject, current: List<TimelineItem>): List<TimelineItem> {
        if (method.endsWith("/delta") || method == "item/reasoning/summaryTextDelta") {
            val key = params.optString("itemId", "stream-" + method)
            val delta = params.optString("delta")
            val items = current.toMutableList()
            val index = items.indexOfLast { it.id == key }
            if (index < 0) items += TimelineItem(
                key, method.substringAfter("item/").substringBefore("/"), delta, "streaming")
            else items[index] = items[index].copy(text = items[index].text + delta)
            return items
        }
        val item = params.optJSONObject("item") ?: return current
        val id = item.optString("id", "item-" + System.nanoTime())
        val kind = item.optString("type", "event")
        val description = when (kind) {
            "userMessage" -> {
                val parts = item.optJSONArray("content") ?: JSONArray()
                (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                    .filter(String::isNotBlank).joinToString("\n")
            }
            "commandExecution" -> item.optString("command")
            "fileChange" -> "文件变更：" +
                (item.optJSONObject("changes")?.length() ?: 0) + " 个文件"
            "mcpToolCall" -> item.optString("tool")
            else -> item.optString("text")
        }
        val items = current.toMutableList()
        val existing = TimelineReconciler.position(items, id, kind)
        val displayedKind = if (kind == "userMessage") "user" else kind
        val displayedText = description.ifBlank {
            if (existing >= 0) items[existing].text else "正在处理…"
        }
        val entry = TimelineItem(id, displayedKind, displayedText,
            item.optString("status"),
            if (displayedKind == "user") "" else item.toString(2))
        if (existing < 0) items += entry else items[existing] = entry
        return items
    }
}
