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
    fun project(input: SessionDirectoryInput, binding: (String) -> RemoteExecutionBinding?): SessionDirectoryPresentation {
        val scope = input.scope
        val raw = if (scope.sectionId == null || scope.fullText) records(input.response) else input.sectionRows.orEmpty()
        val matching = if (scope.fullText && scope.query.isNotBlank())
            input.searchResult?.takeIf { it.first == (scope.archived to scope.query.trim()) }?.second.orEmpty()
        else if (scope.fullText) emptyList() else {
            val roots = raw.filter { SessionListPolicy.titleMatches(it, scope.query) }
                .mapTo(hashSetOf()) { input.versions.root(SessionListPolicy.id(it)) }
            raw.filter { input.versions.root(SessionListPolicy.id(it)) in roots }
        }
        val rows = input.versions.rows(raw, matching).filter { row ->
            if (!scope.projectOnly) true else {
                val bound = binding(SessionListPolicy.id(row))
                val remote = scope.remote
                if (remote != null) bound != null && bound.id == remote.id && bound.cwd == remote.cwd
                else bound == null && row.optString("cwd") == scope.workspace
            }
        }
        val pins = rows.map(SessionListPolicy::id).filter { input.versions.root(it) in input.pins }.toSet()
        return SessionDirectoryPresentation(SessionListPolicy.ordered(rows, pins), pins)
    }
}

@Composable
internal fun collectSessionDirectory(vm: CodexViewModel, input: SessionDirectoryInput): State<Result<SessionDirectoryPresentation>?> {
    val latest by rememberUpdatedState(input)
    return produceCodexUiState<Result<SessionDirectoryPresentation>?>(null, vm, input.scope) {
        snapshotFlow { latest }.collectLatest { request ->
            if (!request.available) value = null
            else {
                val prepared = codexLoadResult {
                    // Project-only rows consult persistent remote ownership, so that work belongs on IO.
                    withContext(if (request.scope.projectOnly) Dispatchers.IO else Dispatchers.Default) {
                        SessionDirectoryEngine.project(request, vm.remoteBindings::forThread)
                    }
                }
                if (latest == request) value = prepared
            }
        }
    }
}
