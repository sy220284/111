package io.github.sy220284.codexandroid

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Preserve native ordering, including interleaved turn IDs and deferred outputs.
 * Payloads live in a private disk spool; only offsets, metadata and one turn are
 * retained in memory. Both file export and clipboard use this single path. */
internal class ThreadCopyHistory(private val request: suspend (String, JSONObject) -> JSONObject) {
    suspend fun write(threadId: String, spool: File, output: Appendable, includeActivity: Boolean = false,
        onProgress: suspend (Int) -> Unit = {}) {
        require(threadId.isNotBlank()) { "缺少会话标识" }
        val offsets = linkedMapOf<String, MutableList<Long>>()
        val seenCursors = hashSetOf<String>()
        val seenItems = hashSetOf<Pair<String, String>>()
        RandomAccessFile(spool, "rw").use { store ->
            store.setLength(0)
            var cursor: String? = null
            do {
                currentCoroutineContext().ensureActive()
                val params = JSONObject().put("threadId", threadId).put("sortDirection", "asc").put("limit", 100)
                cursor?.let { params.put("cursor", it) }
                val page = request("thread/items/list", params)
                val data = page.getJSONArray("data")
                for (index in 0 until data.length()) {
                    currentCoroutineContext().ensureActive()
                    var entry = data.getJSONObject(index)
                    val turn = entry.getString("turnId")
                    val id = entry.getJSONObject("item").getString("id")
                    check(turn.isNotBlank() && id.isNotBlank()) { "历史缺少条目标识" }
                    check(seenItems.add(turn to id)) { "历史条目重复，请重新读取" }
                    if (entry.optBoolean("outputDeferred")) {
                        val detail = request("thread/items/read", JSONObject().put("threadId", threadId)
                            .put("turnId", turn).put("itemIds", JSONArray().put(id))).getJSONArray("data")
                        check(detail.length() == 1) { "历史输出读取不完整" }
                        entry = detail.getJSONObject(0)
                        check(entry.getString("turnId") == turn && entry.getJSONObject("item").getString("id") == id &&
                            !entry.optBoolean("outputDeferred")) { "历史输出归属不一致或未读取完整" }
                    }
                    val bytes = entry.getJSONObject("item").toString().toByteArray(Charsets.UTF_8)
                    offsets.getOrPut(turn) { mutableListOf() }.add(store.filePointer)
                    store.writeInt(bytes.size)
                    store.write(bytes)
                }
                onProgress(seenItems.size)
                cursor = historyCursor(page)
                if (cursor != null) check(seenCursors.add(cursor)) { "历史分页重复，请重新读取" }
            } while (cursor != null)
            val outcomes = HistoryTurnOutcomes(threadId, request).load(offsets.keys)
            val renderer = ThreadMarkdownExporter.Stream(output, includeActivity)
            for ((id, positions) in offsets) {
                currentCoroutineContext().ensureActive()
                val items = JSONArray()
                for (position in positions) {
                    currentCoroutineContext().ensureActive()
                    store.seek(position)
                    val bytes = ByteArray(store.readInt())
                    store.readFully(bytes)
                    items.put(JSONObject(bytes.toString(Charsets.UTF_8)))
                }
                val turn = outcomes[id]
                renderer.turn(JSONObject().put("id", id).put("items", items)
                    .put("status", turn?.optString("status") ?: "unverified")
                    .put("error", turn?.opt("error") ?: JSONObject.NULL))
            }
            renderer.finish()
        }
    }
}
