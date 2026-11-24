package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
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

/** A transient choice for this draft, not a permanent work mode. */
@Composable
internal fun ComposerFollowUpChoices(selected: String, onSelect: (String) -> Unit,
    enabled: Boolean, canGuide: Boolean) {
    Column(Modifier.fillMaxWidth().testTag("composer-follow-up-mode"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodexFilterChip(selected = selected == "steer", onClick = { onSelect("steer") },
                modifier = Modifier.weight(1f).testTag("composer-mode-steer"),
                enabled = enabled && canGuide,
                label = { Text("引导任务", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { CodexIcon(CodexGlyph.EditNote, null, Modifier.size(18.dp)) })
            CodexFilterChip(selected = selected == "queue", onClick = { onSelect("queue") },
                modifier = Modifier.weight(1f).testTag("composer-mode-queue"),
                enabled = enabled,
                label = { Text("排队发送", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { CodexIcon(CodexGlyph.TaskRunning, null, Modifier.size(18.dp)) })
        }
        Text(when {
            selected == "steer" && !canGuide -> "等待当前执行回合就绪"
            selected == "steer" -> "立即发送到当前任务，调整接下来的执行"
            else -> "当前任务完成后按顺序执行"
        }, style = MaterialTheme.typography.labelSmall, color = Ink.muted)
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
                CodexIcon(CodexGlyph.TaskRunning, null, Modifier.size(18.dp),
                    tint = if (paused) Ink.warning else Ink.muted)
                Spacer(Modifier.width(6.dp))
                Text(if (paused) "队列已暂停 · " + count else "待执行 · " + count,
                    modifier = Modifier.weight(1f), color = if (paused) Ink.warning else Ink.parchment,
                    style = MaterialTheme.typography.labelLarge)
                CodexIconAction(onClick = { expanded = !expanded },
                    enabled = !compact, modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.ExpandMore, if (expanded) "收起后续指令" else "展开后续指令")
                }
                CodexTextAction(onClick = onManage, modifier = Modifier.testTag("chat-queued-followups"),
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("管理") }
            }
            if (!running) CodexSecondaryButton(onClick = onStart, enabled = canStart,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (paused) "恢复执行" else "开始执行", maxLines = 2)
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
                        CodexTextAction(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("查看全部后续指令") }
                }
            }
        }
    }
}
