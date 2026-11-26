package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object SessionListPolicy {
    fun id(row: JSONObject) = PresentationPolicy.meaningful(row.optString("id"))
    fun title(row: JSONObject) = PresentationPolicy.threadTitle(row.optString("name"), row.optString("preview"))
    fun recency(row: JSONObject) = sequenceOf("recencyAt", "updatedAt", "createdAt")
        .map { row.optLong(it, 0) }.firstOrNull { it > 0 } ?: 0
    private data class SortKey(val row: JSONObject, val id: String, val pinned: Boolean, val timestamp: Long)
    fun ordered(rows: List<JSONObject>, pinned: Set<String>, sort: SessionSort = SessionSort.Recent): List<JSONObject> {
        val seen = hashSetOf<String>()
        return rows.mapNotNull { row ->
            val id = id(row)
            if (id.isBlank() || !seen.add(id)) null else SortKey(row, id, id in pinned, sort.timestamp(row))
        }.sortedWith(compareByDescending<SortKey> { it.pinned }.thenByDescending { it.timestamp }
            .thenByDescending { it.id }).map { it.row }
    }
    fun titleMatches(row: JSONObject, query: String) =
        query.isBlank() || title(row).contains(query.trim(), ignoreCase = true)
    fun unread(receipt: JSONObject?) = receipt?.optJSONObject("firstUnread") != null
    fun cursor(response: JSONObject): String? =
        PresentationPolicy.meaningful(response.optString("nextCursor")).takeIf { it.isNotBlank() }
    fun activity(row: JSONObject, live: ThreadActivity?, running: Boolean, approvals: Int): ThreadActivity {
        val observed = live ?: ThreadActivityPolicy.fromThread(row)
        return when {
            approvals > 0 -> ThreadActivity.WaitingApproval
            observed == ThreadActivity.WaitingApproval || observed == ThreadActivity.WaitingAnswer -> observed
            running -> ThreadActivity.Running
            else -> observed
        }
    }
    fun mergeRows(previous: List<JSONObject>, changed: Set<String>, incoming: List<JSONObject>): List<JSONObject> =
        (previous.filterNot { id(it) in changed } + incoming).filter { id(it).isNotBlank() }.distinctBy(::id)

    fun params(archived: Boolean, cursor: String? = null) =
        JSONObject().put("limit", 100).put("archived", archived)
            .put("sortKey", "recency_at").put("sortDirection", "desc").apply {
                cursor?.let { put("cursor", it) }
            }
}

/** One process-owned cache, atomic complete snapshots and no user-visible pagination. */
internal class SessionListController(private val vm: CodexViewModel, private val scope: CoroutineScope) {
    private val preferences = vm.getApplication<android.app.Application>()
        .getSharedPreferences("session-list-preferences", Context.MODE_PRIVATE)
    val recentSearches = SessionSearchHistoryController(vm.getApplication<android.app.Application>(), scope)
    val pins = MutableStateFlow(preferences.getStringSet("pinned", emptySet()).orEmpty().toSet())
    val receipts = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val searching = MutableStateFlow(false)
    val searchResult = MutableStateFlow<Pair<Pair<Boolean, String>, List<JSONObject>>?>(null)
    val searchError = MutableStateFlow("")
    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var dirty = false
    private var archiveVisible = false
    private var archiveDirty = true
    private var identity: String? = null
    private var identityEpoch = 0L
    private val index = SessionIndexStore(vm.getApplication<android.app.Application>().cacheDir.resolve("session-index.db"))
    private var allowCacheRead = true
    private val pendingIds = linkedSetOf<String>()
    private var lastSuccess = -60_000L
    private var activeSearch: Pair<Boolean, String>? = null
    private var searchVersion = 0L
    private var receiptVersion = 0L
    private val receiptEvents = mutableMapOf<String, Long>()
    private val marking = mutableSetOf<String>()
    private fun connected() = vm.bridge.status.value == EngineStatus.Connected && !AppDataTransfer.state.value.busy
    private fun feedback(message: String) {
        scope.launch { vm.actionFeedback.emit(ActionFeedbackReceipt(message, isError = true)) }
    }
    fun togglePin(id: String) {
        setPinned(setOf(id), id !in pins.value)
    }
    fun setPinned(ids: Set<String>, pinned: Boolean): Boolean {
        val targets = ids.filter(String::isNotBlank)
        val next = if (pinned) pins.value + targets else pins.value - targets.toSet()
        if (next == pins.value) return true
        if (!preferences.edit().putStringSet("pinned", next).commit()) {
            feedback("置顶状态保存失败，请重试")
            return false
        }
        pins.value = next
        return true
    }
    fun removePin(id: String) {
        if (id in pins.value) togglePin(id)
        receipts.value = receipts.value - id
        receiptEvents.remove(id)
    }
    fun receipt(id: String, value: JSONObject) {
        if (id.isBlank() || PresentationPolicy.meaningful(value.optString("revision")).isBlank()) return
        receiptEvents[id] = ++receiptVersion
        receipts.value = receipts.value + (id to value)
    }
    fun markRead(id: String, unread: Boolean, supplied: JSONObject? = null) {
        val seen = supplied ?: receipts.value[id] ?: return
        val rev = PresentationPolicy.meaningful(seen.optString("revision"))
        if (rev.isBlank() || SessionListPolicy.unread(seen) == unread || !connected() || !marking.add(id)) return
        val owner = identityEpoch
        scope.launch {
            val started = receiptVersion
            try {
                val response = vm.bridge.request("thread/readState/update",
                    JSONObject().put("threadId", id).put("expectedRevision", rev)
                        .put("operation", JSONObject().put("type", if (unread) "unread" else "read")))
                if (owner == identityEpoch && (receiptEvents[id] ?: 0) <= started) receipt(id, response.getJSONObject("readState"))
            } catch (timeout: TimeoutCancellationException) {
                if (owner == identityEpoch) { feedback("已读状态更新超时，请重试"); refresh() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owner == identityEpoch) {
                    feedback("已读状态更新失败：" + PresentationPolicy.readableError(failure.message ?: "请重新操作"))
                    refresh()
                }
            } finally { if (owner == identityEpoch) marking.remove(id) }
        }
    }
    fun accountReady(account: JSONObject) {
        val next = SessionIndexStore.identity(account)
        if (identity != null && identity != next) invalidateIdentity()
        if (identity == next) return
        identity = next
        val owner = identityEpoch
        scope.launch {
            val cached = if (allowCacheRead) index.read(next) else null
            if (owner != identityEpoch || identity != next) return@launch
            if (cached != null && vm.data.value["sidebarThreads"] == null)
                vm.data.value = vm.data.value + mapOf("sidebarThreads" to cached, "threads" to cached)
            // If native synchronization finished before the account receipt, persist its actual result.
            val current = vm.data.value["sidebarThreads"]
            if (lastSuccess >= 0 && current != null && listJob?.isActive != true) {
                try { index.write(next, current, true, emptySet()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.w("SessionIndex", "会话索引保存失败", failure) }
            }
            if (owner == identityEpoch && identity == next && listJob?.isActive != true) refreshIfStale()
        }
    }
    fun invalidateIdentity() {
        identityEpoch += 1
        identity = null
        allowCacheRead = false
        scope.launch {
            try { index.clear() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("SessionIndex", "旧账号索引清理失败", failure) }
        }
        listJob?.cancel()
        listJob = null
        search("", false)
        vm.data.value = vm.data.value - setOf("sidebarThreads", "threads", "archivedThreads")
        vm.loading.value = vm.loading.value - "sidebarThreads"
        vm.errors.value = vm.errors.value - "sidebarThreads"
        receipts.value = emptyMap()
        receiptEvents.clear()
        marking.clear()
        pendingIds.clear()
        archiveDirty = true
        dirty = true
        lastSuccess = -60_000L
    }
    fun showArchive(visible: Boolean) {
        archiveVisible = visible
        if (visible && (archiveDirty || vm.data.value["archivedThreads"] == null)) {
            archiveDirty = true
            drain()
        }
    }
    fun refreshIfStale() {
        if (dirty || android.os.SystemClock.elapsedRealtime() - lastSuccess >= 30_000L) refresh()
    }
    fun refreshPending() { if (dirty || pendingIds.isNotEmpty() || (archiveVisible && archiveDirty)) drain() }

    fun refresh() {
        dirty = true
        drain()
    }

    fun refreshChanged(id: String) {
        if (id.isBlank() || vm.data.value["sidebarThreads"] == null) {
            refresh()
            return
        }
        pendingIds += id
        drain()
    }

    private fun drain() {
        if (!connected() || listJob?.isActive == true) return
        val owner = identityEpoch
        listJob = scope.launch {
            while (owner == identityEpoch && (dirty || pendingIds.isNotEmpty() || (archiveVisible && archiveDirty)) && connected()) {
                val full = dirty
                val ids = if (full) pendingIds.toSet() else pendingIds.take(100).toSet()
                val activeNeeded = full || ids.isNotEmpty()
                val archivedNeeded = archiveVisible && (archiveDirty || full || ids.isNotEmpty())
                val fullArchive = archiveDirty || full || vm.data.value["archivedThreads"] == null
                pendingIds.removeAll(ids)
                dirty = false
                if (archivedNeeded) archiveDirty = false
                else if (activeNeeded) archiveDirty = true
                val started = receiptVersion
                vm.loading.value = vm.loading.value + "sidebarThreads"
                try {
                    val freshReceipts = mutableMapOf<String, JSONObject>()
                    val replaced = mutableSetOf<String>()
                    suspend fun readAll(archived: Boolean, complete: Boolean): JSONObject {
                        val key = if (archived) "archivedThreads" else "sidebarThreads"
                        val rows = linkedMapOf<String, JSONObject>()
                        val cursors = mutableSetOf<String>()
                        var cursor: String? = null
                        if (complete) replaced += records(vm.data.value[key]).map(SessionListPolicy::id)
                        do {
                            val params = SessionListPolicy.params(archived, cursor)
                            if (!complete) params.put("includedThreadIds", JSONArray(ids.toList())).put("useStateDbOnly", true)
                            val page = vm.bridge.request("thread/list", params)
                            check(owner == identityEpoch) { "会话索引所属账号已变化" }
                            for (row in records(page)) {
                                val id = SessionListPolicy.id(row)
                                if (id.isNotBlank()) rows[id] = row
                            }
                            page.optJSONObject("readStates")?.let { states ->
                                states.keys().forEach { id -> states.optJSONObject(id)?.let { freshReceipts[id] = it } }
                            }
                            // Publish the first authoritative page immediately; retain older cached rows
                            // until the final cursor succeeds so scrolling never drops existing history.
                            if (complete && cursor == null && connected()) {
                                val first = JSONObject().put("data", JSONArray(SessionListPolicy.mergeRows(
                                    records(vm.data.value[key]), rows.keys, rows.values.toList())))
                                val changes = if (archived) mapOf(key to first) else mapOf(key to first, "threads" to first)
                                vm.data.value = vm.data.value + changes
                            }
                            cursor = SessionListPolicy.cursor(page)
                            check(cursor == null || cursors.add(cursor)) { "会话列表返回重复游标" }
                        } while (cursor != null)
                        return JSONObject().put("data", JSONArray(rows.values.toList()))
                    }
                    val active = if (activeNeeded) readAll(false, full) else null
                    val archived = if (archivedNeeded) readAll(true, fullArchive) else null
                    if (owner == identityEpoch && connected()) {
                        val newer = receipts.value.filterKeys { (receiptEvents[it] ?: 0) > started }
                        receipts.value = (receipts.value - (replaced + ids)) + freshReceipts + newer
                        fun merged(key: String, incoming: JSONObject, complete: Boolean): JSONObject =
                            if (complete) incoming else JSONObject().put("data",
                                JSONArray(SessionListPolicy.mergeRows(records(vm.data.value[key]), ids, records(incoming))))
                        val changes = mutableMapOf<String, JSONObject>()
                        active?.let {
                            val current = merged("sidebarThreads", it, full)
                            changes["sidebarThreads"] = current
                            changes["threads"] = current
                        }
                        archived?.let { changes["archivedThreads"] = merged("archivedThreads", it, fullArchive) }
                        vm.data.value = vm.data.value + changes
                        vm.errors.value = vm.errors.value - "sidebarThreads"
                        if (full) lastSuccess = android.os.SystemClock.elapsedRealtime()
                        val account = identity
                        val current = changes["sidebarThreads"]
                        if (account != null && current != null && active != null) {
                            try { index.write(account, active, full, ids) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                android.util.Log.w("SessionIndex", "会话索引保存失败", failure)
                                vm.errors.value = vm.errors.value + ("sidebarThreads" to "会话已同步，本机索引保存失败")
                            }
                        }
                        activeSearch?.let { (archived, query) -> search(query, archived) }
                    } else if (owner == identityEpoch) {
                        dirty = dirty || full
                        archiveDirty = archiveDirty || archivedNeeded
                        pendingIds += ids
                    }
                } catch (timeout: TimeoutCancellationException) {
                    if (owner == identityEpoch) {
                        vm.errors.value = vm.errors.value + ("sidebarThreads" to "会话同步超时")
                        dirty = true; archiveDirty = true
                    }
                    break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (owner == identityEpoch) {
                        vm.errors.value = vm.errors.value + ("sidebarThreads" to (failure.message ?: "会话同步失败"))
                        dirty = true; archiveDirty = true
                    }
                    break
                } finally {
                    if (owner == identityEpoch) vm.loading.value = vm.loading.value - "sidebarThreads"
                }
            }
        }
    }
    fun search(query: String, archived: Boolean) {
        val version = ++searchVersion
        searchJob?.cancel()
        searchError.value = ""
        searching.value = false
        val term = query.trim()
        activeSearch = if (term.isBlank()) null else archived to term
        if (searchResult.value?.first != activeSearch || term.isBlank()) searchResult.value = null
        if (term.isBlank()) return
        if (!connected()) { searchError.value = "连接内核后可搜索正文"; return }
        searching.value = true
        searchJob = scope.launch {
            try {
                val rows = linkedMapOf<String, JSONObject>()
                val cursors = mutableSetOf<String>()
                var cursor: String? = null
                do {
                    val page = vm.bridge.request("thread/search", SessionListPolicy.params(archived, cursor)
                        .put("searchTerm", term))
                    for (hit in records(page)) {
                        val row = hit.getJSONObject("thread")
                        val id = SessionListPolicy.id(row)
                        if (id.isNotBlank()) rows[id] = JSONObject(row.toString())
                            .put("searchSnippet", hit.optString("snippet"))
                    }
                    cursor = SessionListPolicy.cursor(page)
                    check(cursor == null || cursors.add(cursor)) { "全文搜索返回重复游标" }
                } while (cursor != null)
                if (version == searchVersion) searchResult.value = (archived to term) to rows.values.toList()
            } catch (timeout: TimeoutCancellationException) {
                if (version == searchVersion) searchError.value = "搜索超时，请重试"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (version == searchVersion) searchError.value = failure.message ?: "搜索失败"
            } finally { if (version == searchVersion) searching.value = false }
        }
    }
}
