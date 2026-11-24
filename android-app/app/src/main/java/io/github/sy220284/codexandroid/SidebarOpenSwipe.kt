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
 * Unclaimed horizontal drags cancel descendant taps on every page. Main pass
 * gives child scrolling, selection and gestures first refusal; consuming the
 * slop-crossing move cancels an ordinary clickable on its Final pass.
 * Only an intentional right swipe from the work screen's left area opens
 * the drawer. The system back edge remains under Android's control.
 */
@Composable
internal fun Modifier.pageHorizontalSwipe(
    enabled: Boolean, canOpenDrawer: Boolean, onOpen: () -> Unit
): Modifier {
    val currentOpen = rememberUpdatedState(onOpen)
    val currentCanOpen = rememberUpdatedState(canOpenDrawer)
    if (!enabled) return this
    return pointerInput(Unit) {
        val edgeWidth = 96.dp.toPx()
        val openDistance = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val eligible = currentCanOpen.value && down.position.x <= edgeWidth
            var horizontal = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.size != 1) break
                val change = event.changes.first()
                if (change.id != down.id || change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!change.pressed) {
                    if (horizontal) {
                        change.consume()
                        if (eligible && currentCanOpen.value && dx >= openDistance &&
                            dx >= abs(dy) * 1.5f) currentOpen.value()
                    }
                    break
                }
                if (!horizontal) {
                    if (abs(dy) >= viewConfiguration.touchSlop && abs(dy) >= abs(dx)) break
                    if (abs(dx) < viewConfiguration.touchSlop) continue
                    if (abs(dx) < abs(dy) * 1.5f) continue
                    horizontal = true
                }
                change.consume()
            }
        }
    }
}
