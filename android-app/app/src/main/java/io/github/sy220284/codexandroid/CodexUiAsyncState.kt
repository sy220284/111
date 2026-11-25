package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A changed key owns a new state and a new cancellable effect. Work may switch to
 * Default/IO, but all publication to the Compose state resumes on Android Main.
 */
@Composable
internal fun <T> produceCodexUiState(
    initialValue: T, vararg keys: Any?, producer: suspend MutableState<T>.() -> Unit
): State<T> {
    // One structural key avoids Compose's dynamic vararg key lowering issue.
    val state = remember(keys.toList()) { mutableStateOf(initialValue) }
    LaunchedEffect(state) {
        withContext(Dispatchers.Main.immediate) { state.producer() }
    }
    return state
}

/** One-shot data loaders never run blocking file/CPU work in the UI producer.
 * The key owns the result, and withContext checks cancellation before publishing. */
@Composable
internal fun <T> loadCodexUiState(
    initialValue: T, vararg keys: Any?,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    load: suspend () -> T
): State<T> = produceCodexUiState(initialValue, *keys) {
    value = withContext(dispatcher) { load() }
}

/** Expected I/O failures belong to the owning result; cancellation is control flow. */
internal suspend fun <T> codexLoadResult(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (failure: Exception) {
    Result.failure(failure)
}
