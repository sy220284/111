package io.github.sy220284.codexandroid

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 拖动只占用编号触控区；列表其余区域仍正常滚动，点击可走同一个排序菜单。 */
@Composable
internal fun AtlasQueueDragHandle(position: Int, total: Int, enabled: Boolean, itemId: String,
    onClick: () -> Unit, onStart: () -> Unit, onMove: (Float) -> Unit,
    onEnd: () -> Unit, onCancel: () -> Unit) {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)
        .testTag("queue-drag-" + itemId)
        .semantics { contentDescription = "第 ${position} 项，长按拖动排序" }
        .pointerInput(itemId, enabled) {
            if (enabled) detectDragGesturesAfterLongPress(
                onDragStart = { start() },
                onDragEnd = { end() },
                onDragCancel = { cancel() }
            ) { change, amount ->
                change.consume()
                move(amount.y)
            }
        }) {
        AtlasSequenceBadge(position, total)
    }
}
