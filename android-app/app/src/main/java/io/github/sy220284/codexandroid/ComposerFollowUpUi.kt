package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

/** Native Codex distinguishes submit (Enter) from queue (Tab).
 * On touch screens this independent queue action lives outside the input surface.
 * It submits directly; no sticky send mode or additional action is required. */
@Composable
internal fun ComposerQueueAction(visible: Boolean, enabled: Boolean, onQueue: () -> Unit) {
    val reduced by UiPreferences.reducedMotion.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = visible,
        enter = expandVertically(tween(CodexMotion.duration(CodexMotion.selection, reduced))) +
            fadeIn(tween(CodexMotion.duration(CodexMotion.selection, reduced))),
        exit = shrinkVertically(tween(CodexMotion.duration(CodexMotion.release, reduced))) +
            fadeOut(tween(CodexMotion.duration(CodexMotion.release, reduced)))) {
        Row(Modifier.fillMaxWidth().testTag("composer-queue-action"),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Ink.panel,
                border = BorderStroke(1.dp, Ink.controlBorder)) {
                CodexIconAction(onClick = onQueue, enabled = visible && enabled,
                    modifier = Modifier.size(48.dp).testTag("composer-send-queue")) {
                    CodexIcon(CodexGlyph.ViewList, "排队发送，当前任务结束后执行",
                        Modifier.size(22.dp), tint = if (enabled) Ink.parchment else Ink.muted)
                }
            }
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
    val reduced by UiPreferences.reducedMotion.collectAsStateWithLifecycle()
    var expanded by remember(thread) { mutableStateOf(false) }
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
