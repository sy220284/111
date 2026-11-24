package io.github.sy220284.codexandroid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Only a deliberate right swipe starting in the left area of the work screen
 * opens the drawer. Main pass lets child scroll/selection gestures take priority.
 * Leave the system back-gesture edge under Android's control.
 */
@Composable
internal fun Modifier.sidebarOpenSwipe(enabled: Boolean, onOpen: () -> Unit): Modifier {
    val currentOpen = rememberUpdatedState(onOpen)
    if (!enabled) return this
    return pointerInput(Unit) {
        val edgeWidth = 96.dp.toPx()
        val openDistance = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x > edgeWidth) return@awaitEachGesture
            var horizontal = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.size != 1) break
                val change = event.changes.first()
                if (change.id != down.id || change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!change.pressed) {
                    if (horizontal && dx >= openDistance) {
                        change.consume()
                        currentOpen.value()
                    }
                    break
                }
                if (!horizontal) {
                    if (abs(dy) >= viewConfiguration.touchSlop && abs(dy) >= abs(dx)) break
                    if (abs(dx) < viewConfiguration.touchSlop) continue
                    if (dx <= 0f || dx < abs(dy) * 1.5f) break
                    horizontal = true
                }
                change.consume()
            }
        }
    }
}
