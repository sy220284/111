package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compose state is always published by the Android UI thread. Producers retain
 * their composition cancellation and frame clock, and explicitly move expensive
 * computation or disk operations to Default/IO. Keys belong to the calling surface.
 */
@Composable
internal fun <T> produceCodexUiState(
    initialValue: T, vararg keys: Any?, producer: suspend ProduceStateScope<T>.() -> Unit
): State<T> = key(*keys) {
    produceState(initialValue) {
        val destination = this
        withContext(Dispatchers.Main.immediate) { destination.producer() }
    }
}
