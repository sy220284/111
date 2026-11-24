package io.github.sy220284.codexandroid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

/** Both windows must belong to the ordinary Codex meter; never combine different model buckets. */
internal data class SidebarQuota(val weekly: Float?, val fiveHours: Float?) {
    fun description(stale: Boolean, loading: Boolean): String {
        fun percent(value: Float?) = value?.let { "${(it * 100).roundToInt()}%" } ?: "未提供"
        val suffix = when {
            stale && (weekly != null || fiveHours != null) -> "，上次读取"
            loading && weekly == null && fiveHours == null -> "，正在读取"
            stale -> "，暂不可用"
            else -> ""
        }
        return "周额度剩余 ${percent(weekly)}，五小时额度剩余 ${percent(fiveHours)}$suffix"
    }

    companion object {
        fun from(response: JSONObject?): SidebarQuota {
            val groups = response?.optJSONObject("rateLimitsByLimitId")
            val legacy = response?.optJSONObject("rateLimits")
            val legacyId = legacy?.opt("limitId") as? String
            val meter = groups?.optJSONObject("codex") ?: legacy?.takeIf {
                legacyId == "codex" || (legacyId.isNullOrBlank() && (groups == null || groups.length() == 0))
            }
            val windows = listOfNotNull(meter?.optJSONObject("primary"), meter?.optJSONObject("secondary"))
            fun remaining(minutes: Int): Float? {
                val window = windows.singleOrNull {
                    (it.opt("windowDurationMins") as? Number)?.toDouble() == minutes.toDouble()
                } ?: return null
                val used = (window.opt("usedPercent") as? Number)?.toDouble() ?: return null
                return if (used.isFinite() && used in 0.0..100.0) (1.0 - used / 100.0).toFloat() else null
            }
            return SidebarQuota(remaining(7 * 24 * 60), remaining(5 * 60))
        }
    }
}

/** 24dp artwork in the same 48dp touch target as the account and settings icons. */
@Composable
internal fun SidebarQuotaButton(snapshot: JSONObject?, stale: Boolean, loading: Boolean, onClick: () -> Unit) {
    val quota = remember(snapshot) { SidebarQuota.from(snapshot) }
    CodexIconAction(onClick = onClick, modifier = Modifier.size(48.dp).testTag("nav-limits")
        .semantics {
            contentDescription = "用量与额度"
            stateDescription = quota.description(stale, loading)
        }) {
        // Reset animation ownership when the account changes or data disappears.
        key(snapshot?.optString("accountId"), quota.weekly != null, quota.fiveHours != null) {
            SidebarQuotaArtwork(quota)
        }
    }
}

@Composable
private fun SidebarQuotaArtwork(quota: SidebarQuota) {
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val dark by UiPreferences.darkMode.collectAsState()
    val weekly by animateFloatAsState(quota.weekly ?: 0f,
        tween(CodexMotion.duration(CodexMotion.success, reduced)), label = "weekly-quota")
    val short by animateFloatAsState(quota.fiveHours ?: 0f,
        tween(CodexMotion.duration(CodexMotion.success, reduced)), label = "five-hour-quota")
    val violet = if (dark) Color(0xFFB59AFB) else Color(0xFF7651C5)
    val violetDeep = if (dark) Color(0xFF8050DB) else Color(0xFF5637A1)
    val teal = if (dark) Color(0xFF54D8DB) else Color(0xFF087E88)
    val track = Ink.controlBorder
    val well = Ink.elevated
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        val outer = size.minDimension / 2f - stroke / 2f
        val inner = outer - stroke / 2f - 1.dp.toPx()
        val arcTop = Offset(center.x - outer, center.y - outer)
        val arcSize = Size(outer * 2f, outer * 2f)
        drawCircle(well, inner)
        drawCircle(track, inner, style = Stroke(.65.dp.toPx()))
        if (quota.weekly == null) {
            drawLine(track, Offset(center.x - inner * .32f, center.y),
                Offset(center.x + inner * .32f, center.y), 1.2.dp.toPx(), StrokeCap.Round)
        } else if (weekly >= 1f) {
            drawCircle(Brush.verticalGradient(listOf(violet, violetDeep)), inner)
        } else if (weekly > 0f) {
            val y = center.y + inner - 2f * inner * weekly
            val wave = inner * .11f * (4f * weekly * (1f - weekly))
            val liquid = Path().apply {
                moveTo(center.x - inner, y)
                cubicTo(center.x - inner * .45f, y - wave, center.x + inner * .45f, y + wave, center.x + inner, y)
                lineTo(center.x + inner, center.y + inner)
                lineTo(center.x - inner, center.y + inner)
                close()
            }
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(
                center.x - inner, center.y - inner, center.x + inner, center.y + inner)) }
            clipPath(clip) { drawPath(liquid, Brush.verticalGradient(listOf(violet, violetDeep))) }
        }
        if (quota.fiveHours == null) {
            drawCircle(track, outer, style = Stroke(stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
        } else {
            drawCircle(track, outer, style = Stroke(stroke))
            if (short >= 1f) drawCircle(teal, outer, style = Stroke(stroke))
            else if (short > 0f) drawArc(teal, -90f, short * 360f, false,
                topLeft = arcTop, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
    }
}
