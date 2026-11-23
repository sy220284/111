package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only account metrics. Percentages and reset windows are supplied by the
 * server; no usage amount or eligibility is inferred when a field is missing.
 */
@Composable
fun AccountMetricsPage(id: String, vm: CodexViewModel, navigate: (String) -> Unit) {
    val requestedTab = remember(id) { SettingsDeepLink.take("usage") }
    var selectedTab by rememberSaveable(id, requestedTab) {
        mutableStateOf(if (id == "limits" || requestedTab == "limits") "limits" else "usage")
    }
    val data by vm.data.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val result = data[selectedTab]
    val usage = selectedTab == "usage"
    val method = if (usage) "account/usage/read" else "account/rateLimits/read"
    LaunchedEffect(selectedTab) { vm.refresh(selectedTab, method) }
    ScreenColumn {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedTab == "usage",
                onClick = { selectedTab = "usage" },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("用量") }
            )
            SegmentedButton(
                selected = selectedTab == "limits",
                onClick = { selectedTab = "limits" },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("额度") }
            )
        }
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (usage) Icons.Outlined.Insights else Icons.Outlined.Speed,
                    null, tint = Ink.copper)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (usage) "账号使用概况" else "额度窗口",
                        style = MaterialTheme.typography.titleMedium)
                    Text("与当前登录账号同步",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                }
                CodexRefreshIcon(busy = selectedTab in loading,
                    onRefresh = { vm.refresh(selectedTab, method) },
                    label = "刷新账号数据")
            }
            if (selectedTab in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        errors[selectedTab]?.let { ErrorText(it) }
        if (result == null && selectedTab !in loading) {
            when {
                engineStatus != EngineStatus.Connected -> {
                    CodexRecoveryState("连接后可查看账号数据",
                        "本机运行核心尚未连接，连接后读取用量与额度。",
                        "重新连接", { vm.connect() }, { navigate("diagnostics") })
                }
                selectedTab in errors -> {
                    CodexRecoveryState("账号数据读取失败",
                        "检查登录状态后可重试读取。",
                        "重新读取", { vm.refresh(selectedTab, method) },
                        { navigate("diagnostics") })
                }
                else -> CodexEmptyState("登录后查看账号数据",
                    "当前没有返回账号用量或额度，请检查登录状态。",
                    actionLabel = "前往账号登录", onAction = { navigate("account") })
            }
        }
        if (usage && result != null) {
            val summary = result.optJSONObject("summary")
            if (summary == null) {
                CodexEmptyState("尚无使用量汇总", "当前账号尚未返回统计记录。")
            } else {
                val lifetime = summary.opt("lifetimeTokens")
                val peak = summary.opt("peakDailyTokens")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricNumberCard("累计 Tokens", formatMetricNumber(lifetime),
                        "服务端累计统计", Modifier.weight(1f))
                    MetricNumberCard("单日最高", formatMetricNumber(peak),
                        "Tokens / 天", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricNumberCard("当前连续天数",
                        formatMetricNumber(summary.opt("currentStreakDays")),
                        "天", Modifier.weight(1f))
                    MetricNumberCard("历史最长连续",
                        formatMetricNumber(summary.opt("longestStreakDays")),
                        "天", Modifier.weight(1f))
                }
                val longest = summary.opt("longestRunningTurnSec")
                if (longest is Number) {
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Text("最长单次任务执行", color = Ink.muted,
                            style = MaterialTheme.typography.labelMedium)
                        Text(longest.toLong().toString() + " 秒",
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            val buckets = result.optJSONArray("dailyUsageBuckets")
            if (buckets != null && buckets.length() > 0) {
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text("最近每日用量", style = MaterialTheme.typography.titleMedium)
                    val recent = (0 until buckets.length())
                        .mapNotNull { buckets.optJSONObject(it) }
                        .takeLast(10)
                    val maxTokens = recent.maxOfOrNull { it.optLong("tokens") }?.coerceAtLeast(1L) ?: 1L
                    recent.forEach { bucket ->
                        val tokens = bucket.optLong("tokens").coerceAtLeast(0L)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(bucket.optString("startDate").takeLast(5),
                                modifier = Modifier.width(53.dp), color = Ink.muted,
                                style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(
                                progress = { tokens.toFloat().div(maxTokens).coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(8.dp),
                                color = Ink.copper, trackColor = Ink.border)
                            Spacer(Modifier.width(9.dp))
                            Text(formatMetricNumber(tokens), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text("条形宽度按这组记录中的最大日用量归一化。",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!usage && result != null) {
            when (result.opt("ordinaryUsageAllowed")) {
                false -> CodexNotice("服务端暂不允许普通额度请求。请检查账号限额或计划。",
                    NoticeTone.Warning)
                true -> CodexNotice("服务端确认当前允许普通额度请求。",
                    NoticeTone.Success)
            }
            val groups = result.optJSONObject("rateLimitsByLimitId")
            if (groups != null && groups.length() > 0) {
                for (bucket in groups.keys()) {
                    val snapshot = groups.optJSONObject(bucket) ?: continue
                    LimitBucketCard(snapshot.optString("limitName").ifBlank { bucket }, snapshot)
                }
            } else {
                result.optJSONObject("rateLimits")?.let {
                    LimitBucketCard(it.optString("limitName").ifBlank { "当前账号" }, it)
                }
            }
            if (groups == null && result.optJSONObject("rateLimits") == null) {
                CodexEmptyState("未提供限额窗口", "当前服务端没有返回可展示的限额信息。")
            }
        }
    }
}

private fun formatMetricNumber(raw: Any?): String =
    if (raw is Number) NumberFormat.getIntegerInstance(Locale.CHINA).format(raw.toLong())
    else "未提供"

@Composable
private fun MetricNumberCard(label: String, value: String, note: String, modifier: Modifier) {
    MaterialPanel(modifier) {
        Text(label, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ink.parchment)
        Text(note, color = Ink.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LimitBucketCard(title: String, snapshot: JSONObject) {
    MaterialPanel(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val plan = snapshot.optString("planType")
        if (plan.isNotBlank()) Text("账号计划已读取", color = Ink.muted,
            style = MaterialTheme.typography.bodySmall)
        val reached = snapshot.optString("rateLimitReachedType")
        if (reached.isNotBlank() && reached != "null") {
            CodexNotice("当前账号已达到相应额度限制。", NoticeTone.Warning)
        }
        val windows = listOf("primary" to "主要窗口", "secondary" to "次要窗口")
        var shown = 0
        windows.forEach { (field, label) ->
            val window = snapshot.optJSONObject(field) ?: return@forEach
            shown++
            val used = window.optInt("usedPercent").coerceIn(0, 100)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Text("$used%", style = MaterialTheme.typography.titleMedium,
                    color = if (used >= 100) Ink.red else Ink.copper)
            }
            LinearProgressIndicator(progress = { used / 100f },
                modifier = Modifier.fillMaxWidth().height(9.dp),
                color = if (used >= 100) Ink.red else Ink.copper,
                trackColor = Ink.border)
            val duration = window.optLong("windowDurationMins", -1L)
            val until = window.optLong("resetsAt", -1L)
            if (duration > 0) Text("窗口 " + duration + " 分钟",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            if (until > 0) Text("下次重置：" +
                SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(until * 1000L)),
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            DividerLine()
        }
        if (shown == 0) Text("服务端未提供使用率窗口", color = Ink.muted)
        snapshot.optJSONObject("credits")?.let { credits ->
            Text("额外额度", style = MaterialTheme.typography.titleMedium)
            Text(if (credits.optBoolean("unlimited")) "无限额度"
                else credits.optString("balance").ifBlank { "余额未提供" },
                color = Ink.muted)
        }
    }
}
