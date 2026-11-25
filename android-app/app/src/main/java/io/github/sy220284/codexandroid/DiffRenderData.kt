package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DiffSummary(val files: Int, val additions: Int, val deletions: Int)
internal data class PreparedDiff(val raw: String, val files: List<DiffSection>, val lineNumbers: List<List<DiffLineNumbers>>)

private const val INLINE_DIFF_LIMIT = 32_768

/** Small payloads are cheap; large diffs must not monopolize the Android UI thread. */
@Composable
private fun <T : Any> rememberDiffValue(raw: String, calculate: (String) -> T): State<T?> =
    key(raw) {
        if (raw.length <= INLINE_DIFF_LIMIT) remember(raw) { mutableStateOf<T?>(calculate(raw)) }
        else loadCodexUiState<T?>(null, raw, dispatcher = Dispatchers.Default) { calculate(raw) }
    }

@Composable
internal fun rememberDiffSummary(raw: String): State<DiffSummary?> = rememberDiffValue(raw) {
    val files = UnifiedDiff.parse(it)
    DiffSummary(files.size, files.sumOf(DiffSection::additions), files.sumOf(DiffSection::deletions))
}

@Composable
internal fun rememberPreparedDiff(raw: String, owner: String?): State<PreparedDiff?> = key(owner) {
    // Keep a coherent previous snapshot during parsing, but never across conversations.
    val initial = remember(raw) { if (raw.length <= INLINE_DIFF_LIMIT) prepareDiff(raw) else null }
    if (initial != null) remember(raw) { mutableStateOf<PreparedDiff?>(initial) }
    else loadCodexUiState<PreparedDiff?>(null, raw, dispatcher = Dispatchers.Default) { prepareDiff(raw) }
}

private fun prepareDiff(raw: String): PreparedDiff {
    val files = UnifiedDiff.parse(raw)
    return PreparedDiff(raw, files, files.map { file -> UnifiedDiff.annotateLines(file.lines) })
}
