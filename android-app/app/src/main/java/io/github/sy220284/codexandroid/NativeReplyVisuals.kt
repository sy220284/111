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
        TextButton(onClick = { selected = if (selected == index) -1 else index },
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
internal fun ReplyBranchGraph(rows: List<List<String>>) {
    val nodes = rows.flatMap { listOf(it[0], it[2]) }.distinct()
    nodes.forEach { node ->
        Surface(Modifier.fillMaxWidth(), color = Ink.recessed, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(node, fontWeight = FontWeight.SemiBold)
                rows.filter { it[0] == node }.forEach { edge ->
                    Text("↳ " + edge[1] + " → " + edge[2], style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
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
            Text("√", fontSize = font)
            Column { HorizontalDivider(color = Ink.parchment); ReplyMathLayout(value.value, small) }
        }
        is ReplyMath.Script -> Row(verticalAlignment = Alignment.CenterVertically) {
            ReplyMathLayout(value.base, small)
            Column { value.superscript?.let { ReplyMathLayout(it, true) }
                value.subscript?.let { ReplyMathLayout(it, true) } }
        }
    }
}
