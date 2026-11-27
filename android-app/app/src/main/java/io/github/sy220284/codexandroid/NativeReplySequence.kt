package io.github.sy220284.codexandroid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeReplySequence(rows: List<List<String>>) {
    val diagram = remember(rows) { ReplySequence.diagram(rows) }
    val participantIndex = remember(diagram) { diagram.participants.mapIndexed { index, participant -> participant.id to index }.toMap() }
    val labels = remember(diagram) { diagram.participants.associate { it.id to it.label } }
    var selected by remember(rows) { mutableIntStateOf(-1) }
    val lane = 128.dp
    val header = 52.dp
    val step = 64.dp
    val border = Ink.border
    val active = Ink.copper
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("message-sequence-diagram")) {
        Box(Modifier.size(lane * diagram.participants.size, header + step * diagram.messages.size + 12.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                fun laneX(index: Int) = lane.toPx() * (index + .5f)
                diagram.participants.indices.forEach { index ->
                    val x = laneX(index)
                    drawLine(border, Offset(x, header.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                }
                diagram.messages.forEachIndexed { index, message ->
                    val x1 = laneX(participantIndex.getValue(message.sender))
                    val x2 = laneX(participantIndex.getValue(message.receiver))
                    val y = (header + step * index + 48.dp).toPx()
                    val color = if (selected < 0 || selected == index) active else border
                    drawLine(color, Offset(x1, y), Offset(x2, y), strokeWidth = 2.dp.toPx(),
                        pathEffect = if (message.arrow.startsWith("--")) dash else null)
                    if (message.arrow.endsWith(">>")) {
                        val direction = if (x2 > x1) 1 else -1
                        val back = x2 - direction * 9.dp.toPx()
                        drawLine(color, Offset(x2, y), Offset(back, y - 5.dp.toPx()), 2.dp.toPx())
                        drawLine(color, Offset(x2, y), Offset(back, y + 5.dp.toPx()), 2.dp.toPx())
                    }
                }
            }
            diagram.participants.forEachIndexed { index, participant ->
                Surface(Modifier.offset(x = lane * index + 4.dp, y = 4.dp).size(lane - 8.dp, header - 8.dp),
                    color = Ink.recessed, shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, border)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(participant.label, Modifier.padding(horizontal = 6.dp), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            diagram.messages.forEachIndexed { index, message ->
                val left = minOf(participantIndex.getValue(message.sender), participantIndex.getValue(message.receiver))
                val right = maxOf(participantIndex.getValue(message.sender), participantIndex.getValue(message.receiver))
                CodexTextAction(onClick = { selected = if (selected == index) -1 else index },
                    modifier = Modifier.offset(x = lane * left + lane / 2, y = header + step * index)
                        .width(lane * (right - left)).height(48.dp)) {
                    Text(message.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Text("横向滑动查看完整时序图，点击消息查看详情。", color = Ink.muted,
        style = MaterialTheme.typography.labelSmall)
    diagram.messages.getOrNull(selected)?.let { message ->
        Text(labels.getValue(message.sender) + " → " + labels.getValue(message.receiver),
            style = MaterialTheme.typography.labelLarge)
        Text(message.label, style = MaterialTheme.typography.bodyMedium)
    }
}
