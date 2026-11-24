package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Read only the data belonging to this surface; unrelated RPC receipts cannot invalidate it. */
@Composable
internal fun <T> StateFlow<Map<String, T>>.collectEntries(vararg keys: String): State<Map<String, T>> {
    val selected = keys.toSet()
    val source = this
    fun select(entries: Map<String, T>): Map<String, T> = buildMap {
        for (name in selected) if (entries.containsKey(name)) put(name, entries.getValue(name))
    }
    val flow = remember(source, selected) { source.map(::select).distinctUntilChanged() }
    // Changing the selected conversation must not expose its previous map for even one frame.
    return key(source, selected) { flow.collectAsState(initial = select(source.value)) }
}

/** Source-only projection: output events must not redraw controls that only need a running flag. */
@Composable
internal fun <T, R> StateFlow<T>.collectProjection(project: (T) -> R): State<R> {
    val source = this
    val flow = remember(source, project) { source.map(project).distinctUntilChanged() }
    return key(source, project) { flow.collectAsState(initial = project(source.value)) }
}

/** Conflate presentation snapshots at display cadence; the source retains every processed event.
 * No delayed timer, event dropping, or independent copy of the protocol state. */
@Composable
internal fun <T> StateFlow<T>.collectFrameState(identity: Any? = Unit): State<T> {
    val source = this
    return key(source, identity) {
        produceState(source.value, source) {
            source.collect { latest ->
                if (latest != value) {
                    withFrameNanos { }
                    value = source.value
                }
            }
        }
    }
}
