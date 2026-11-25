package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.key
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
): State<T> = key(*keys) {
    val state = remember { mutableStateOf(initialValue) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main.immediate) { state.producer() }
    }
    state
}
