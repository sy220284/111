package io.github.sy220284.codexandroid

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bound the live Compose timeline while keeping every older item locally readable.
 * Full authoritative history continues to live in the Codex core. The scratch
 * index is discarded on thread change; it is not a second data source.
 */
internal class TimelineWindowStore(private val cache: File) {
    companion object {
        const val WINDOW_SIZE = 480
        const val COMPACT_AT = 720
        const val PAGE_SIZE = 120
    }

    private val lock = Mutex()
    private val positions = ArrayList<Long>()
    private var threadId: String? = null
    private var visibleStart = 0
    private var epoch = 0L
    val olderCount = MutableStateFlow(0)

    private fun resetFile() {
        cache.parentFile?.mkdirs()
        RandomAccessFile(cache, "rw").use { it.setLength(0) }
        positions.clear()
        visibleStart = 0
    }

    private fun persist(items: List<TimelineItem>) {
        if (items.isEmpty()) return
        RandomAccessFile(cache, "rw").use { file ->
            file.seek(file.length())
            for (item in items) {
                positions += file.filePointer
                val json = JSONObject()
                    .put("id", item.id).put("kind", item.kind)
                    .put("text", item.text).put("status", item.status)
                    .put("evidence", item.evidence).put("turnId", item.turnId).put("phase", item.phase).toString()
                file.write(json.toByteArray(Charsets.UTF_8))
                file.write(10)
            }
            file.fd.sync()
        }
    }

    suspend fun replace(id: String, history: List<TimelineItem>, owner: Long = 0): List<TimelineItem> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (owner < epoch) return@withLock history
                epoch = owner
                resetFile()
                threadId = id
                persist(history.take((history.size - WINDOW_SIZE).coerceAtLeast(0)))
                visibleStart = positions.size
                olderCount.value = visibleStart
                history.takeLast(WINDOW_SIZE)
            }
        }

    suspend fun compact(id: String?, visible: List<TimelineItem>, owner: Long = 0): List<TimelineItem> {
        if (id == null || visible.size <= COMPACT_AT) return visible
        return withContext(Dispatchers.IO) {
            lock.withLock {
                if (owner < epoch) return@withLock visible
                if (owner > epoch || threadId != id) {
                    epoch = owner
                    resetFile()
                    threadId = id
                }
                val removing = visible.size - WINDOW_SIZE
                // Earlier-page entries are already on disk; avoid storing duplicates.
                val alreadyPersisted = removing.coerceAtMost(positions.size - visibleStart)
                persist(visible.subList(alreadyPersisted, removing))
                visibleStart += removing
                olderCount.value = visibleStart
                visible.drop(removing)
            }
        }
    }

    suspend fun readEarlier(id: String, owner: Long = 0,
        accept: (List<TimelineItem>) -> Boolean = { true }): List<TimelineItem> = lock.withLock {
            if (owner != epoch || threadId != id || visibleStart == 0) return@withLock emptyList()
            val start = (visibleStart - PAGE_SIZE).coerceAtLeast(0)
            val previous = withContext(Dispatchers.IO) {
                val items = ArrayList<TimelineItem>(visibleStart - start)
                RandomAccessFile(cache, "r").use { file ->
                for (index in start until visibleStart) {
                    file.seek(positions[index])
                    val end = if (index + 1 < positions.size) positions[index + 1]
                        else file.length()
                    val length = end - positions[index]
                    if (length !in 1..Int.MAX_VALUE.toLong())
                        throw IOException("历史分页记录长度无效")
                    val bytes = ByteArray(length.toInt())
                    file.readFully(bytes)
                    val entry = JSONObject(String(bytes, Charsets.UTF_8).trimEnd('\n'))
                    items += TimelineItem(
                        entry.getString("id"), entry.getString("kind"),
                        entry.getString("text"), entry.optString("status"),
                        entry.optString("evidence"), entry.optString("turnId"), entry.optString("phase"))
                }
            }
                items
            }
            // Caller commits the accepted page on its own dispatcher before the cursor moves.
            if (!accept(previous)) return@withLock emptyList()
            visibleStart = start
            olderCount.value = start
            previous
    }

    suspend fun clear(owner: Long = 0) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (owner < epoch) return@withLock
            epoch = owner
            resetFile()
            threadId = null
            olderCount.value = 0
        }
    }
}
