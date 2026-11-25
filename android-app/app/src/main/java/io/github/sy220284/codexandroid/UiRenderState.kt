package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A subscription owns its initial snapshot and subsequent updates together.
 * Reading the initial value is a one-time subscription operation; the stream remains the
 * source of truth and immediately emits its newest value when the collector starts. */
private data class UiSubscription<R>(val initial: R, val updates: Flow<R>)

private fun <T, R> StateFlow<T>.uiSubscription(project: (T) -> R): UiSubscription<R> =
    UiSubscription(project(value), map(project).distinctUntilChanged())

/** Read only the data belonging to this surface; unrelated RPC receipts cannot invalidate it. */
@Composable
internal fun <T> StateFlow<Map<String, T>>.collectEntries(vararg keys: String): State<Map<String, T>> {
    val selected = keys.toSet()
    val source = this
    val subscription = remember(source, selected) {
        source.uiSubscription { entries ->
            buildMap {
                for (name in selected) if (entries.containsKey(name)) put(name, entries.getValue(name))
            }
        }
    }
    // A different conversation starts a different subscription with its own initial snapshot.
    return key(subscription) { subscription.updates.collectAsState(initial = subscription.initial) }
}

/** Source-only projection: output events must not redraw controls that only need a running flag. */
@Composable
internal fun <T, R> StateFlow<T>.collectProjection(project: (T) -> R): State<R> {
    val source = this
    val subscription = remember(source, project) { source.uiSubscription(project) }
    return key(subscription) { subscription.updates.collectAsState(initial = subscription.initial) }
}

/** Publish at display cadence; the source retains every processed event. */
@Composable
internal fun <T> StateFlow<T>.collectFrameState(identity: Any? = Unit): State<T> {
    val source = this
    val subscription = remember(source, identity) { source.uiSubscription { it } }
    return key(subscription) {
        produceCodexUiState(subscription.initial, subscription) {
            subscription.updates.collect { latest ->
                if (latest != value) {
                    withFrameNanos { }
                    // A frame can include multiple source events; publish their latest complete state.
                    value = source.value
                }
            }
        }
    }
}
