package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun TimelineItem.active() = status in setOf("inProgress", "in_progress", "running", "streaming")
private fun TimelineItem.failed() = kind == "error" || status in setOf("failed", "declined", "interrupted", "cancelled")

@Composable
internal fun TimelineActivityGroup(group: List<TimelineItem>, onTurnAction: ((TimelineItem, Boolean) -> Unit)? = null) {
    if (group.size == 1 && group.first().kind in setOf("user", "agent", "agentMessage", "plan", "planProgress", "error",
            "imageGeneration", "enteredReviewMode", "exitedReviewMode", "contextCompaction", "collabAgentToolCall", "subAgentActivity")) {
        TimelineStepCard(group.first(), onTurnAction)
        return
    }
    val active = group.any { it.active() }
    val failed = group.count { it.failed() }
    var expanded by rememberSaveable(group.first().id) { mutableStateOf(false) }
    val summary = group.groupingBy { PresentationPolicy.timelineKind(it.kind) }.eachCount()
        .entries.joinToString(" · ") { (kind, count) -> if (count == 1) kind else "$kind $count 项" }
    Column(Modifier.fillMaxWidth().testTag("chat-activity-group")
        .border(1.dp, Ink.border, RoundedCornerShape(12.dp))
        .background(Ink.canvas, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodexIcon(if (failed > 0) CodexGlyph.TaskError else if (active) CodexGlyph.TaskRunning else CodexGlyph.AutoAwesome,
                null, tint = if (failed > 0) Ink.red else if (active) Ink.copper else Ink.jade, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text((if (active) "正在处理 " else "已处理 ") + group.size + " 项活动" +
                    if (failed > 0) " · $failed 项未成功" else "",
                    color = if (failed > 0) Ink.red else Ink.muted, style = MaterialTheme.typography.labelLarge)
                Text(summary, style = MaterialTheme.typography.labelSmall, color = Ink.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CodexIcon(if (expanded) CodexGlyph.ExpandMore else CodexGlyph.ChevronRight,
                if (expanded) "收起活动" else "展开活动", tint = Ink.muted)
        }
        CodexReveal(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                group.forEach { item -> key(item.id) { TimelineStepCard(item, onTurnAction) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineStepCard(item: TimelineItem, onTurnAction: ((TimelineItem, Boolean) -> Unit)? = null) {
    val user = item.kind in setOf("user", "userMessage")
    val assistant = item.kind in setOf("agent", "agentMessage")
    val active = item.active()
    val failure = item.failed()
    val tone = if (failure) Ink.red else Ink.muted
    val title = PresentationPolicy.timelineKind(item.kind)
    val clipboard = LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var readerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var evidenceOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var rawOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    val detail = item.hasEvidence
    val raw = remember(item) { if (assistant) null else item.metadata() }
    val shownText = item.text.ifBlank {
        when {
            active -> "正在处理…"
            item.kind == "reasoning" -> "思考已结束"
            else -> title
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        if (user || assistant) {
            Surface(Modifier.fillMaxWidth(if (user) .88f else 1f)
                .testTag(if (user) "chat-user-message" else "chat-assistant-message"),
                color = if (user) Ink.recessed else Ink.canvas, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = if (user) 13.dp else 2.dp, vertical = 8.dp)) {
                    if (assistant) NativeMessageMarkdown(shownText)
                    else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            CodexIcon(CodexGlyph.PersonOutline, null, tint = Ink.muted, modifier = Modifier.size(16.dp))
                            Text(if (item.status == "sending") "正在发送" else "你的指令", color = Ink.muted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(5.dp))
                        SelectionContainer { Text(shownText, style = MaterialTheme.typography.bodyLarge) }
                    }
                    if (user) NativeTimelineMedia(item, raw)
                    if (!active && (user || item.phase != "commentary")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodexIconAction(onClick = { clipboard.setText(AnnotatedString(item.text)) },
                                modifier = Modifier.size(48.dp)) {
                                CodexIcon(CodexGlyph.ContentCopy, "复制消息", tint = Ink.muted, modifier = Modifier.size(18.dp))
                            }
                            Box(Modifier.size(48.dp).testTag("timeline-header-" + item.id)
                                .combinedClickable(onClick = { menuOpen = true }, onLongClick = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    menuOpen = true
                                }), contentAlignment = Alignment.Center) {
                                CodexIcon(CodexGlyph.MoreHoriz, "消息操作", tint = Ink.muted)
                            }
                        }
                    }
                }
            }
        } else {
            val icon = when (item.kind) {
                "commandExecution" -> CodexGlyph.Terminal
                "fileChange" -> CodexGlyph.Diff
                "mcpToolCall", "dynamicToolCall" -> CodexGlyph.Hub
                "plan", "planProgress" -> CodexGlyph.ViewList
                "error" -> CodexGlyph.TaskError
                else -> CodexGlyph.AutoAwesome
            }
            Column(Modifier.fillMaxWidth().testTag("chat-execution-step")) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("timeline-header-" + item.id)
                    .clickable { if (detail) evidenceOpen = true else readerOpen = true }
                    .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodexIcon(icon, null, tint = tone, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (item.kind in setOf("plan", "planProgress")) title else shownText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (failure) Ink.red else Ink.parchment, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val output = if (active) item.commandOutputTail(4096) else ""
                        val statusText = PresentationPolicy.timelineStatus(item.status)
                        val caption = listOfNotNull(title.takeUnless { it == shownText },
                            statusText.takeIf(String::isNotBlank),
                            if (raw != null && !raw.isNull("exitCode"))
                                "退出码 " + raw.optInt("exitCode") else null,
                            if (raw != null && !raw.isNull("durationMs"))
                                raw.optLong("durationMs").toString() + " 毫秒" else null).joinToString(" · ")
                        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall, color = tone)
                        if (active && output.isNotBlank() && output != "null") {
                            Text(output.lineSequence().filter(String::isNotBlank).lastOrNull().orEmpty().take(240),
                                style = MaterialTheme.typography.labelSmall, color = Ink.muted, fontFamily = FontFamily.Monospace,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    CodexIconAction(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)
                        .testTag("timeline-more-" + item.id)) {
                        CodexIcon(CodexGlyph.MoreHoriz, "消息操作", tint = tone)
                    }
                }
                if (item.kind in setOf("plan", "planProgress")) NativeMessageMarkdown(item.text)
                if (item.kind == "imageGeneration" || item.kind == "imageView") NativeTimelineMedia(item, raw)
            }
        }
    }
    if (menuOpen) CodexActionSheet(title = title, onDismiss = { menuOpen = false }, actions = buildList {
        if (item.text.isNotBlank()) {
            add(CodexSheetAction("复制内容", CodexGlyph.ContentCopy,
                { clipboard.setText(AnnotatedString(item.text)) }))
            add(CodexSheetAction("全文阅读", CodexGlyph.OpenInFull, { readerOpen = true }))
        }
        if (user && item.turnId.isNotBlank() && onTurnAction != null) {
            add(CodexSheetAction("从此回合回退", CodexGlyph.History,
                { onTurnAction(item, false) }, destructive = true))
        }
        if (assistant && item.turnId.isNotBlank() && onTurnAction != null && !active && item.phase != "commentary") {
            add(CodexSheetAction("从此回合创建分支", CodexGlyph.CallSplit,
                { onTurnAction(item, true) }))
        }
        if (detail) {
            add(CodexSheetAction("执行详情", CodexGlyph.Description, { evidenceOpen = true }))
            add(CodexSheetAction("原始执行记录", CodexGlyph.Terminal, { rawOpen = true },
                description = "包含命令、路径和原始输出，请谨慎分享"))
        }
    })
    if (readerOpen) FullTextReaderDialog(title, item.text) { readerOpen = false }
    if (evidenceOpen) CodexEvidenceSheet(title = "$title · 执行详情", payload = item.evidence,
        onDismiss = { evidenceOpen = false })
    if (rawOpen) FullTextReaderDialog("原始执行记录", TimelineEvidenceText.render(item)) { rawOpen = false }
}
