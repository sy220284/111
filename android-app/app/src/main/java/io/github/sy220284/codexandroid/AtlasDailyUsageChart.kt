package io.github.sy220284.codexandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONObject

internal data class DailyUsageSample(val date: LocalDate?, val tokens: Long?)
internal object DailyUsagePolicy {
    fun sample(date: String, raw: Any?): DailyUsageSample {
        val day = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { null }
        val tokens = (raw as? Number)?.takeIf { it.toDouble().isFinite() && it.toDouble() >= 0.0 }?.toLong()
        return DailyUsageSample(day, tokens)
    }
    fun select(all: List<DailyUsageSample>, days: Int?): List<DailyUsageSample> {
        val ordered = all.sortedWith(compareBy<DailyUsageSample> { it.date == null }.thenBy { it.date })
        if (days == null) return ordered
        val latest = ordered.mapNotNull { it.date }.maxOrNull() ?: return emptyList()
        val start = latest.minusDays((days - 1).toLong())
        return ordered.filter { it.date != null && !it.date.isBefore(start) }
    }
}

/** 仅按服务端已返回日期及用量展示；缺失字段保留未知，绝不当成零。 */
@Composable
internal fun AtlasDailyUsageChart(buckets: List<JSONObject>) {
    val all = buckets.map { DailyUsagePolicy.sample(it.optString("startDate"), it.opt("tokens")) }
    var period by rememberSaveable { mutableStateOf("7") }
    val hasDates = all.any { it.date != null }
    val visible = DailyUsagePolicy.select(all, if (hasDates) period.toIntOrNull() else null)
    MaterialPanel(Modifier.fillMaxWidth().testTag("daily-usage-chart")) {
        AtlasListHeading("每日用量", visible.size, suffix = "条")
        if (hasDates) AtlasCompactChoice(period,
            listOf("7" to "最近7日", "30" to "最近30日", "all" to "全部"),
            { period = it }, tagPrefix = "usage-period-")
        val maxTokens = visible.mapNotNull { it.tokens }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        visible.forEach { sample ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sample.date?.toString()?.takeLast(5) ?: "日期未知",
                    modifier = Modifier.widthIn(min = 53.dp), color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
                val tokens = sample.tokens
                if (tokens != null) {
                    Box(Modifier.weight(1f).height(7.dp).background(Ink.border, RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth((tokens.toDouble() / maxTokens).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().background(Ink.copper, RoundedCornerShape(4.dp)))
                    }
                } else Spacer(Modifier.weight(1f))
                Text(tokens?.let { NumberFormat.getIntegerInstance(Locale.CHINA).format(it) } ?: "未提供",
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(if (hasDates) "周期按返回记录的最新日期计算；条形按当前记录最大日用量比较。"
            else "服务端未提供有效日期，无法按周期筛选。",
            style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        if (hasDates && period != "all" && all.any { it.date == null }) {
            Text("部分记录缺少日期，可切换全部查看。", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        }
    }
}
