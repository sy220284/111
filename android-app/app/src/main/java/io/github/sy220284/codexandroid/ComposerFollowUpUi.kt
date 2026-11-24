package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Temporary icon-only choice. Each native radio target remains 48dp, while
 * the visible circle is smaller so it aligns with the existing toolbar. */
@Composable
internal fun ComposerFollowUpChoices(selected: String, onSelect: (String) -> Unit,
    visible: Boolean, enabled: Boolean, canGuide: Boolean, reducedMotion: Boolean) {
    AnimatedVisibility(visible = visible,
        enter = expandHorizontally(tween(CodexMotion.duration(CodexMotion.selection, reducedMotion))) +
            fadeIn(tween(CodexMotion.duration(CodexMotion.selection, reducedMotion))),
        exit = shrinkHorizontally(tween(CodexMotion.duration(CodexMotion.release, reducedMotion))) +
            fadeOut(tween(CodexMotion.duration(CodexMotion.release, reducedMotion)))) {
        Row(Modifier.testTag("composer-follow-up-mode").selectableGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            ComposerFollowUpIcon("steer", CodexGlyph.EditNote, "引导任务，立即追加到当前回合",
                selected == "steer", visible && enabled && canGuide, reducedMotion, onSelect)
            ComposerFollowUpIcon("queue", CodexGlyph.ViewList, "排队发送，当前任务完成后执行",
                selected == "queue", visible && enabled, reducedMotion, onSelect)
        }
    }
}

@Composable
private fun ComposerFollowUpIcon(mode: String, glyph: CodexGlyph, description: String,
    selected: Boolean, enabled: Boolean, reducedMotion: Boolean, onSelect: (String) -> Unit) {
    val fill by animateColorAsState(
        targetValue = if (selected) Ink.copper.copy(alpha = if (enabled) .16f else .07f) else Color.Transparent,
        animationSpec = tween(CodexMotion.duration(CodexMotion.selection, reducedMotion)),
        label = "follow-up-selection")
    Box(Modifier.size(48.dp).testTag("composer-mode-" + mode)
        .codexSelectable(selected, enabled) { onSelect(mode) }
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).background(fill, CircleShape),
            contentAlignment = Alignment.Center) {
            CodexIcon(glyph, null, Modifier.size(22.dp),
                tint = if (!enabled) Ink.muted.copy(alpha = .5f)
                    else if (selected) Ink.copper else Ink.muted)
        }
    }
}

internal fun queuedInputSummary(item: JSONObject): String {
    val input = item.optJSONArray("input") ?: JSONArray()
    return (0 until input.length()).mapNotNull { input.optJSONObject(it) }.mapNotNull { part ->
        when (part.optString("type")) {
            "text" -> part.optString("text").takeIf { it.isNotBlank() }
            "image", "localImage" -> "图片附件"
            "skill" -> "技能：" + part.optString("name")
            else -> "其他任务输入"
        }
    }.joinToString("\n").ifBlank { "非文本任务输入" }
}

/** Only real queued messages occupy space above the composer. Full editing and ordering
 * use the same queue sheet, avoiding a second implementation of queue mutations. */
@Composable
internal fun ComposerQueuePreview(thread: String, items: List<JSONObject>, running: Boolean,
    paused: Boolean, hasMore: Boolean, compact: Boolean, canStart: Boolean,
    onManage: () -> Unit, onStart: () -> Unit) {
    val reduced by UiPreferences.reducedMotion.collectAsState()
    var expanded by remember(thread) { mutableStateOf(true) }
    val count = items.size.toString() + if (hasMore) "+" else ""
    Surface(Modifier.fillMaxWidth().testTag("composer-queue-preview"),
        shape = RoundedCornerShape(16.dp), color = Ink.panel,
        border = BorderStroke(1.dp, Ink.border)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.ViewList, if (paused) "队列已暂停" else "待执行队列",
                    Modifier.size(20.dp), tint = if (paused) Ink.warning else Ink.muted)
                Spacer(Modifier.width(6.dp))
                Text(count, modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "待执行 " + count + " 条指令" },
                    color = if (paused) Ink.warning else Ink.parchment,
                    style = MaterialTheme.typography.labelLarge)
                if (!running) CodexIconAction(onClick = onStart, enabled = canStart,
                    modifier = Modifier.size(48.dp).testTag("composer-queue-start")) {
                    CodexIcon(CodexGlyph.PlayArrow, if (paused) "恢复执行队列" else "开始执行队列",
                        tint = if (canStart) Ink.parchment else Ink.muted)
                }
                CodexIconAction(onClick = { expanded = !expanded },
                    enabled = !compact, modifier = Modifier.size(48.dp)) {
                    CodexIcon(if (expanded) CodexGlyph.ExpandLess else CodexGlyph.ExpandMore,
                        if (expanded) "收起后续指令" else "展开后续指令")
                }
                CodexIconAction(onClick = onManage,
                    modifier = Modifier.size(48.dp).testTag("chat-queued-followups")) {
                    CodexIcon(CodexGlyph.MoreHoriz, "管理后续指令")
                }
            }
            AnimatedVisibility(visible = expanded && !compact,
                enter = fadeIn(tween(CodexMotion.duration(CodexMotion.reveal, reduced))),
                exit = fadeOut(tween(CodexMotion.duration(CodexMotion.press, reduced)))) {
                Column {
                    items.take(2).forEachIndexed { index, item ->
                        val summary = remember(item) { queuedInputSummary(item) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AtlasSequenceBadge(index + 1, items.size)
                            Spacer(Modifier.width(8.dp))
                            Text(summary, modifier = Modifier.weight(1f), maxLines = 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            CodexIconAction(onClick = onManage, modifier = Modifier.size(48.dp)) {
                                CodexIcon(CodexGlyph.MoreVert, "管理第${index + 1}项指令")
                            }
                        }
                    }
                    if (items.size > 2 || hasMore)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CodexIconAction(onClick = onManage) {
                                CodexIcon(CodexGlyph.MoreHoriz, "查看全部后续指令")
                            }
                        }
                }
            }
        }
    }
}
