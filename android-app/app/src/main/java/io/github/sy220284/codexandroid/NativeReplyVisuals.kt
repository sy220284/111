package io.github.sy220284.codexandroid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReplyDataChart(block: MessageBlock) {
    val rows = block.rows
    val values = rows.map { it[1].toDouble() }
    val min = minOf(0.0, values.minOrNull() ?: 0.0)
    val max = maxOf(0.0, values.maxOrNull() ?: 0.0)
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    val palette = listOf(Ink.copper, Ink.jade, Ink.red, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    var selected by remember(block.source) { mutableIntStateOf(-1) }
    val lineColor = Ink.copper
    val axisColor = Ink.border
    if (block.kind == "pie") {
        val total = values.sum()
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val diameter = minOf(size.width, size.height) * .88f
            val top = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            var angle = -90f
            values.forEachIndexed { index, value ->
                val sweep = (value / total * 360).toFloat()
                drawArc(palette[index % palette.size].copy(alpha = if (selected < 0 || selected == index) 1f else .38f),
                    angle, sweep, true, topLeft = top, size = Size(diameter, diameter))
                angle += sweep
            }
        }
    } else {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            fun y(v: Double) = (size.height * (1 - (v - min) / range)).toFloat()
            val zero = y(0.0)
            drawLine(axisColor, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
            val slot = size.width / values.size
            if (block.kind == "bar") {
                values.forEachIndexed { index, v ->
                    val top = minOf(y(v), zero)
                    drawRect(lineColor.copy(alpha = if (selected < 0 || selected == index) 1f else .35f),
                        topLeft = Offset(slot * index + slot * .18f, top),
                        size = Size(slot * .64f, kotlin.math.abs(y(v) - zero)))
                }
            } else {
                val points = values.mapIndexed { index, v ->
                    Offset(slot * (index + .5f), y(v).coerceIn(3.dp.toPx(), size.height - 3.dp.toPx()))
                }
                points.zipWithNext().forEach { (a, b) -> drawLine(lineColor, a, b, strokeWidth = 2.dp.toPx()) }
                points.forEachIndexed { index, at -> drawCircle(lineColor, if (selected == index) 5.dp.toPx() else 3.dp.toPx(), at) }
            }
        }
    }
    // Values and selection remain accessible independently of the chart drawing.
    rows.forEachIndexed { index, row ->
        CodexTextAction(onClick = { selected = if (selected == index) -1 else index },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (block.kind == "pie") Box(Modifier.size(10.dp).background(palette[index % palette.size], RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(6.dp))
            Text(row[0], Modifier.weight(1f), color = Ink.parchment,
                fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
            Text(row[1], color = Ink.parchment)
        }
    }
}

@Composable
internal fun ReplyBranchGraph(rows: List<List<String>>, source: String) {
    val direction = source.lineSequence().map(String::trim).firstOrNull { it.startsWith("flowchart ") || it.startsWith("graph ") }?.substringAfter(' ')?.trim()?.removeSuffix(";") ?: "TD"
    val layout = remember(rows, direction) { ReplyGraph.layout(rows, direction) }
    val nodeWidth = 148.dp
    val nodeHeight = 70.dp
    val horizontalGap = 44.dp
    val verticalGap = 52.dp
    val border = Ink.border
    val edgeColor = Ink.copper
    var selected by remember(rows) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(Modifier.size((nodeWidth + horizontalGap) * layout.columns + 16.dp,
            (nodeHeight + verticalGap) * layout.layers + 16.dp)) {
            Canvas(Modifier.matchParentSize()) {
                fun point(node: ReplyGraph.Node) = Offset(8.dp.toPx() + node.column * (nodeWidth + horizontalGap).toPx() + nodeWidth.toPx() / 2,
                    8.dp.toPx() + node.layer * (nodeHeight + verticalGap).toPx() + nodeHeight.toPx() / 2)
                layout.edges.forEach { edge ->
                    val from = layout.nodes.first { it.name == edge[0] }
                    val to = layout.nodes.first { it.name == edge[2] }
                    val a = point(from); val b = point(to)
                    val delta = b - a
                    val length = delta.getDistance().coerceAtLeast(1f)
                    val direction = delta / length
                    val radius = minOf(nodeWidth.toPx() / (2 * kotlin.math.abs(direction.x).coerceAtLeast(0.0001f)),
                        nodeHeight.toPx() / (2 * kotlin.math.abs(direction.y).coerceAtLeast(0.0001f)))
                    val start = a + direction * radius
                    val end = b - direction * radius
                    val color = if (selected == null || selected == from.name || selected == to.name) edgeColor else border
                    drawLine(color, start, end, 2.dp.toPx())
                    val side = Offset(-direction.y, direction.x)
                    drawLine(color, end, end - direction * 10.dp.toPx() + side * 5.dp.toPx(), 2.dp.toPx())
                    drawLine(color, end, end - direction * 10.dp.toPx() - side * 5.dp.toPx(), 2.dp.toPx())
                }
            }
            layout.nodes.forEach { node ->
                Surface(Modifier.offset(x = 8.dp + (nodeWidth + horizontalGap) * node.column,
                    y = 8.dp + (nodeHeight + verticalGap) * node.layer).size(nodeWidth, nodeHeight),
                    color = Ink.recessed, shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected == node.name) edgeColor else border)) {
                    CodexTextAction(onClick = { selected = if (selected == node.name) null else node.name },
                        modifier = Modifier.fillMaxSize()) { Text(node.name, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
        }
    }
    rows.filter { selected == null || it[0] == selected || it[2] == selected }.forEach { edge ->
        Text(edge[0] + " → " + edge[2] + if (edge[1].isNotBlank()) " · " + edge[1] else "",
            style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}

@Composable
internal fun ReplyMathLayout(value: ReplyMath, small: Boolean = false) {
    val font = if (small) 13.sp else 20.sp
    when (value) {
        is ReplyMath.Text -> Text(value.value, fontSize = font)
        is ReplyMath.Sequence -> Row(verticalAlignment = Alignment.CenterVertically) {
            value.parts.forEach { ReplyMathLayout(it, small) }
        }
        is ReplyMath.Fraction -> Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(IntrinsicSize.Max)) {
            ReplyMathLayout(value.numerator, true)
            HorizontalDivider(Modifier.fillMaxWidth(), color = Ink.parchment)
            ReplyMathLayout(value.denominator, true)
        }
        is ReplyMath.Root -> Row(verticalAlignment = Alignment.CenterVertically) {
            value.index?.let { ReplyMathLayout(it, true) }
            Text("√", fontSize = font)
            Column(Modifier.width(IntrinsicSize.Max)) { HorizontalDivider(color = Ink.parchment); ReplyMathLayout(value.value, small) }
        }
        is ReplyMath.Matrix -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.left, fontSize = (if (small) 26 else 36).sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                value.rows.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { ReplyMathLayout(it, small) }
                } }
            }
            Text(value.right, fontSize = (if (small) 26 else 36).sp)
        }
        is ReplyMath.Delimited -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.left, fontSize = font); ReplyMathLayout(value.value, small); Text(value.right, fontSize = font)
        }
        is ReplyMath.Accent -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Max)) {
            if (value.kind != "underline") Text(when (value.kind) { "vec" -> "→"; "hat" -> "^"; "dot" -> "·"; "ddot" -> "··"; else -> "¯" }, fontSize = 13.sp)
            ReplyMathLayout(value.value, small)
            if (value.kind == "underline") HorizontalDivider(color = Ink.parchment)
        }
        is ReplyMath.Script -> Row(verticalAlignment = Alignment.CenterVertically) {
            ReplyMathLayout(value.base, small)
            Column { value.superscript?.let { ReplyMathLayout(it, true) }
                value.subscript?.let { ReplyMathLayout(it, true) } }
        }
    }
}
