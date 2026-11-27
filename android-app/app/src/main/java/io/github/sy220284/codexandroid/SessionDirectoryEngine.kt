package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A directory query owns its identity, filters and data snapshot. No list
 * filtering, grouping, remote binding reads or sorting run in composition. */
internal data class SessionDirectoryScope(
    val session: Long?, val account: Long, val sectionId: String?,
    val fullText: Boolean, val archived: Boolean, val query: String,
    val projectOnly: Boolean, val workspace: String, val remote: RemoteExecutionBinding?
)
internal data class SessionDirectoryInput(
    val scope: SessionDirectoryScope, val response: JSONObject?,
    val sectionRows: List<JSONObject>?, val versions: AnswerVersionState,
    val searchResult: Pair<Pair<Boolean, String>, List<JSONObject>>?, val pins: Set<String>
) {
    val available: Boolean get() = if (scope.sectionId != null && !scope.fullText) sectionRows != null
        else response != null || (scope.fullText && searchResult?.first == (scope.archived to scope.query.trim()))
}
internal data class SessionDirectoryPresentation(val rows: List<JSONObject>, val pinned: Set<String>)

internal object SessionDirectoryEngine {
    fun project(input: SessionDirectoryInput, binding: (String) -> RemoteExecutionBinding?): SessionDirectoryPresentation =
        Cache().project(input, binding)

    /** A title edit only selects logical families from a preordered snapshot.
     * Source/version/pin changes invalidate the index; project ownership is
     * checked on every request, including when the title index is reused. */
    class Cache {
        private data class Title(val root: String, val title: String)
        private data class Entry(val row: JSONObject, val id: String, val root: String)
        private data class Index(val input: SessionDirectoryInput, val titles: List<Title>, val entries: List<Entry>)
        private var index: Index? = null

        @Synchronized
        fun project(input: SessionDirectoryInput, binding: (String) -> RemoteExecutionBinding?): SessionDirectoryPresentation {
            val scope = input.scope
            val entries = if (!scope.fullText) {
                val cached = index?.takeIf {
                    val old = it.input
                    old.scope.session == scope.session && old.scope.account == scope.account &&
                        old.scope.sectionId == scope.sectionId && old.scope.archived == scope.archived &&
                        old.response === input.response && old.sectionRows === input.sectionRows &&
                        old.versions == input.versions && old.pins == input.pins
                } ?: run {
                    val raw = if (scope.sectionId == null) records(input.response) else input.sectionRows.orEmpty()
                    val titles = raw.map { Title(input.versions.root(SessionListPolicy.id(it)), SessionListPolicy.title(it)) }
                    Index(input, titles, ordered(input, input.versions.rows(raw))).also { index = it }
                }
                val query = scope.query.trim()
                if (query.isEmpty()) cached.entries else {
                    val roots = cached.titles.asSequence().filter { it.title.contains(query, ignoreCase = true) }
                        .mapTo(hashSetOf()) { it.root }
                    cached.entries.filter { it.root in roots }
                }
            } else {
                val raw = records(input.response)
                val matching = if (scope.query.isNotBlank())
                    input.searchResult?.takeIf { it.first == (scope.archived to scope.query.trim()) }?.second.orEmpty()
                else emptyList()
                ordered(input, input.versions.rows(raw, matching))
            }
            val rows = if (!scope.projectOnly) entries else entries.filter { entry ->
                val bound = binding(entry.id)
                val remote = scope.remote
                if (remote != null) bound != null && bound.id == remote.id && bound.cwd == remote.cwd
                else bound == null && entry.row.optString("cwd") == scope.workspace
            }
            return SessionDirectoryPresentation(rows.map { it.row },
                rows.filter { it.root in input.pins }.mapTo(hashSetOf()) { it.id })
        }

        private fun ordered(input: SessionDirectoryInput, rows: List<JSONObject>): List<Entry> {
            val pins = rows.map(SessionListPolicy::id).filter { input.versions.root(it) in input.pins }.toSet()
            return SessionListPolicy.ordered(rows, pins).map { row ->
                val id = SessionListPolicy.id(row)
                Entry(row, id, input.versions.root(id))
            }
        }
    }
}

@Composable
internal fun collectSessionDirectory(vm: CodexViewModel, input: SessionDirectoryInput): State<Result<SessionDirectoryPresentation>?> {
    val latest by rememberUpdatedState(input)
    // Cache belongs to the account/session controller, not the drawer composition.
    val engine = vm.sessions.directoryCache
    return produceCodexUiState<Result<SessionDirectoryPresentation>?>(null, vm, input.scope) {
        snapshotFlow { latest }.collectLatest { request ->
            if (!request.available) value = null
            else {
                val prepared = codexLoadResult {
                    // Project-only rows consult persistent remote ownership, so that work belongs on IO.
                    withContext(if (request.scope.projectOnly) Dispatchers.IO else Dispatchers.Default) {
                        engine.project(request, vm.remoteBindings::forThread)
                    }
                }
                if (latest == request) value = prepared
            }
        }
    }
}
