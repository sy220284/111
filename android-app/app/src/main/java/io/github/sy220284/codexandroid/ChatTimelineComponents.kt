package io.github.sy220284.codexandroid

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class MessageTurnAction { Fork, Retry, Revert }

private fun TimelineItem.active() = status in setOf("inProgress", "in_progress", "running", "streaming", "sending")
private fun TimelineItem.failed() = kind == "error" || status in setOf("failed", "blocked", "declined", "interrupted", "cancelled", "incomplete", "stopped")

@Composable
internal fun TimelineActivityGroup(
    group: List<TimelineItem>, workspaceRoot: String? = null,
    onTurnAction: ((TimelineItem, MessageTurnAction) -> Unit)? = null,
    version: AnswerVersionChoice? = null, onVersion: ((String) -> Unit)? = null,
    showMessageActions: Boolean = true, actionItem: TimelineItem? = null,
    forceActivity: Boolean = false, note: String? = null, liveProcess: Boolean = false,
    partialCopy: Boolean = false, liveVisibleIds: Set<String>? = null,
    replyPage: ReplyRenderPage? = null, lastPage: Boolean = true, firstPage: Boolean = true
) {
    if (!forceActivity && group.size == 1 && group.first().kind in setOf("user", "userMessage", "agent", "agentMessage", "plan", "planProgress", "error",
            "imageGeneration", "enteredReviewMode", "exitedReviewMode", "contextCompaction", "collabAgentToolCall", "subAgentActivity", "hookRun")) {
        TimelineStepCard(group.first(), workspaceRoot, onTurnAction, version, onVersion,
            showMessageActions && lastPage, actionItem, note.takeIf { lastPage },
            partialCopy = partialCopy && lastPage, replyPage = replyPage,
            firstLongPage = firstPage && !lastPage)
        return
    }
    val active = group.any { it.active() }
    val failed = group.count { it.failed() }
    var expandedOverride by rememberSaveable(group.first().id, liveProcess) { mutableStateOf<Boolean?>(null) }
    val expanded = expandedOverride ?: (liveProcess && (liveVisibleIds == null || liveVisibleIds.isNotEmpty()))
    var visibleCount by rememberSaveable(group.first().id) { mutableIntStateOf(20) }
    val displayed = if (liveProcess && expandedOverride != true && liveVisibleIds != null)
        group.filter { it.id in liveVisibleIds } else group.take(visibleCount)
    val summary = group.groupingBy { if (ProgressiveReplyProjection.commentary(it)) "过程回复" else PresentationPolicy.timelineKind(it.kind) }.eachCount()
        .entries.joinToString(" · ") { (kind, count) -> if (count == 1) kind else "$kind $count 项" }
    Column(Modifier.fillMaxWidth().testTag("chat-activity-group")
        .padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable { expandedOverride = !expanded }.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodexIcon(if (failed > 0) CodexGlyph.TaskError else if (active) CodexGlyph.TaskRunning else CodexGlyph.AutoAwesome,
                null, tint = if (failed > 0) Ink.red else Ink.muted, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text((if (active) "正在执行 · " else if (liveProcess) "执行记录 · " else "已完成执行 · ") + group.size + " 项活动" +
                    if (failed > 0) " · $failed 项未成功" else "",
                    color = if (failed > 0) Ink.red else Ink.muted, style = MaterialTheme.typography.labelLarge)
                if (!expanded) Text(summary, style = MaterialTheme.typography.labelSmall, color = Ink.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CodexIcon(if (expanded) CodexGlyph.ExpandMore else CodexGlyph.ChevronRight,
                if (expanded) "收起活动" else "展开活动", tint = Ink.muted)
        }
        note?.let { Text(it, Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = Ink.muted, style = MaterialTheme.typography.labelSmall) }
        CodexReveal(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (displayed.size < group.size && liveProcess && expandedOverride != true) {
                    CodexTextAction(onClick = { expandedOverride = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("查看其余 ${group.size - displayed.size} 项活动")
                    }
                }
                displayed.forEach { item -> key(item.id) { TimelineStepCard(item, workspaceRoot, onTurnAction,
                    compactActivity = true) } }
                if ((!liveProcess || expandedOverride == true) && visibleCount < group.size)
                    CodexSecondaryButton(onClick = { visibleCount += 20 },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("继续展开执行记录") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TimelineStepCard(
    item: TimelineItem, workspaceRoot: String? = null,
    onTurnAction: ((TimelineItem, MessageTurnAction) -> Unit)? = null,
    version: AnswerVersionChoice? = null, onVersion: ((String) -> Unit)? = null,
    showMessageActions: Boolean = true, actionItem: TimelineItem? = null, note: String? = null,
    compactActivity: Boolean = false, partialCopy: Boolean = false,
    replyPage: ReplyRenderPage? = null, firstLongPage: Boolean = false
) {
    val user = item.kind in setOf("user", "userMessage")
    val assistant = item.kind in setOf("agent", "agentMessage")
    val active = item.active()
    val commentary = ProgressiveReplyProjection.commentary(item)
    val actionMessage = actionItem ?: item
    val failure = item.failed()
    val tone = if (failure) Ink.red else Ink.muted
    val title = PresentationPolicy.timelineKind(item.kind)
    val copy = rememberConversationCopy()
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var readerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var sourceReaderSnapshot by remember(item.id) { mutableStateOf<String?>(null) }
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
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (commentary) {
            Column(Modifier.fillMaxWidth().testTag("chat-commentary").padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NativeMessageMarkdown(shownText, streaming = active)
            }
        } else if (user || assistant) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                if (user) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Surface(Modifier.widthIn(max = maxWidth * .86f).testTag("chat-user-message"),
                            color = Ink.recessed, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SelectionContainer { Text(shownText, style = MaterialTheme.typography.bodyLarge,
                                    color = Ink.parchment) }
                                if (item.status == "sending") Text("正在发送", color = Ink.muted,
                                    style = MaterialTheme.typography.labelSmall)
                                NativeTimelineMedia(item, raw, workspaceRoot)
                            }
                        }
                    }
                } else {
                    if (firstLongPage) CodexTextAction(onClick = { sourceReaderSnapshot = item.text },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("reply-cross-page-read")) {
                        Text("阅读原文 / 跨页选取")
                    }
                    Box(Modifier.fillMaxWidth().testTag("chat-assistant-message").padding(vertical = 4.dp)) {
                        if (replyPage != null) PreparedMarkdownPage(replyPage)
                        else NativeMessageMarkdown(shownText, streaming = active)
                    }
                }
                note?.let { Text(it, color = Ink.muted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(vertical = 4.dp)) }
                if (partialCopy && item.text.isNotBlank()) CodexSecondaryButton(onClick = {
                    copy(item.text, CopyContentFormat.Markdown)
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("partial-reply-copy")) { Text("复制此阶段") }
                if (!active && showMessageActions && (user || item.phase != "commentary")) {
                    if (!user && version != null) FlowRow(Modifier.fillMaxWidth()) {
                        AnswerVersionSwitcher(version, onVersion, actionMessage.id)
                        MessageActionRow(actionMessage, user, onTurnAction, onRead = { readerOpen = true })
                    } else MessageActionRow(actionMessage, user, onTurnAction, onRead = { readerOpen = true })
                } else if (!active && version != null) AnswerVersionSwitcher(version, onVersion, actionMessage.id)
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
                            color = if (failure) Ink.red else if (compactActivity) Ink.muted else Ink.parchment,
                            maxLines = if (compactActivity) 2 else 3, overflow = TextOverflow.Ellipsis)
                        val output = if (active) item.commandOutputTail(4096) else ""
                        val statusText = PresentationPolicy.timelineStatus(item.status)
                        val caption = listOfNotNull(title.takeUnless { it == shownText },
                            statusText.takeIf(String::isNotBlank),
                            if (raw != null && !raw.isNull("exitCode"))
                                "退出码 " + raw.optInt("exitCode") else null,
                            if (raw != null && !raw.isNull("durationMs"))
                                raw.optLong("durationMs").toString() + " 毫秒" else null).joinToString(" · ")
                        if (caption.isNotBlank() && (!compactActivity || active || failure)) Text(caption,
                            style = MaterialTheme.typography.labelSmall, color = tone)
                        if (active && output.isNotBlank() && output != "null") {
                            Text(output.lineSequence().filter(String::isNotBlank).lastOrNull().orEmpty().take(240),
                                style = MaterialTheme.typography.labelSmall, color = Ink.muted, fontFamily = FontFamily.Monospace,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                    CodexIconAction(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)
                        .testTag("timeline-more-" + item.id)) {
                        CodexIcon(CodexGlyph.MoreHoriz, "消息操作", tint = tone)
                    }
                        if (menuOpen && !user && !assistant) CodexFloatingActionMenu(title = title, onDismiss = { menuOpen = false }, actions = buildList {
        if (item.text.isNotBlank()) {
            add(CodexSheetAction("复制内容", CodexGlyph.ContentCopy,
                { copy(item.text, CopyContentFormat.Literal) }))
            add(CodexSheetAction("全文阅读", CodexGlyph.OpenInFull, { readerOpen = true }))
        }
        if (detail) {
            add(CodexSheetAction("执行详情", CodexGlyph.Description, { evidenceOpen = true }))
            add(CodexSheetAction("原始执行记录", CodexGlyph.Terminal, { rawOpen = true },
                description = "包含命令、路径和原始输出，请谨慎分享"))
        }
    })
                    }
                }
                if (item.kind in setOf("plan", "planProgress")) NativeMessageMarkdown(item.text)
                if (item.kind == "imageGeneration" || item.kind == "imageView") NativeTimelineMedia(item, raw, workspaceRoot)
            }
        }
    }
    if (readerOpen) FullTextReaderDialog(title, actionMessage.text,
        if (assistant) CopyContentFormat.Markdown else CopyContentFormat.Literal) { readerOpen = false }
    sourceReaderSnapshot?.let { snapshot ->
        FullTextReaderDialog(title, snapshot, CopyContentFormat.Markdown) { sourceReaderSnapshot = null }
    }
    if (evidenceOpen) CodexEvidenceSheet(title = "$title · 执行详情", payload = item.evidence,
        onDismiss = { evidenceOpen = false })
    if (rawOpen) FullTextReaderDialog("原始执行记录", TimelineEvidenceText.render(item)) { rawOpen = false }
}

/** Only the selected visible message is copied/shared; actions never include tool evidence. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageActionRow(item: TimelineItem, user: Boolean,
    onTurnAction: ((TimelineItem, MessageTurnAction) -> Unit)?, onRead: () -> Unit) {
    val copy = rememberConversationCopy()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember(item.id) { mutableStateOf(false) }
    val hasText = item.text.isNotBlank()
    val hasTurn = item.turnId.isNotBlank()
    val canMutate = hasTurn && onTurnAction != null
    Row(Modifier.testTag("message-actions-" + item.id), verticalAlignment = Alignment.CenterVertically) {
        if (hasText) {
            CodexIconAction(onClick = {
                copy(item.text, if (user) CopyContentFormat.Literal else CopyContentFormat.Markdown)
            }, modifier = Modifier.size(48.dp).testTag("message-copy-" + item.id)) {
                CodexIcon(CodexGlyph.ContentCopy, "复制消息", tint = Ink.muted, modifier = Modifier.size(19.dp))
            }
        }
        if (!user && hasTurn) {
            CodexIconAction(onClick = { onTurnAction?.invoke(item, MessageTurnAction.Retry) },
                enabled = canMutate, modifier = Modifier.size(48.dp).testTag("message-retry-" + item.id)) {
                CodexIcon(CodexGlyph.Retry, "重新回答", tint = Ink.muted.copy(alpha = if (canMutate) 1f else .38f),
                    modifier = Modifier.size(19.dp))
            }
        }
        if (hasText && !user) {
            CodexIconAction(onClick = {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, item.text)
                try {
                    context.startActivity(Intent.createChooser(intent, "分享消息"))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, "没有可用的分享应用，请复制消息", Toast.LENGTH_LONG).show()
                }
            }, modifier = Modifier.size(48.dp).testTag("message-share-" + item.id)) {
                CodexIcon(CodexGlyph.Share, "分享消息", tint = Ink.muted, modifier = Modifier.size(19.dp))
            }
        }
        Box {
            Box(Modifier.size(48.dp).testTag("timeline-header-" + item.id)
                .combinedClickable(role = Role.Button, onClick = { menu = true }, onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                }), contentAlignment = Alignment.Center) {
                CodexIcon(CodexGlyph.MoreHoriz, "消息操作", tint = Ink.muted, modifier = Modifier.size(19.dp))
            }
            CodexPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (hasText) DropdownMenuItem(text = { Text("全文阅读") },
                    leadingIcon = { CodexIcon(CodexGlyph.OpenInFull, null) },
                    onClick = { menu = false; onRead() })
                if (hasText && user) DropdownMenuItem(text = { Text("分享消息") },
                    leadingIcon = { CodexIcon(CodexGlyph.Share, null) },
                    onClick = {
                        menu = false
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.text)
                        try { context.startActivity(Intent.createChooser(intent, "分享消息")) }
                        catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, "没有可用的分享应用，请复制消息", Toast.LENGTH_LONG).show()
                        }
                    })
                if (hasText && !user) DropdownMenuItem(text = { Text("复制纯文本") },
                    leadingIcon = { CodexIcon(CodexGlyph.ContentCopy, null) },
                    onClick = { menu = false; copy(item.text, CopyContentFormat.VisibleText) })
                if (hasTurn && !user) DropdownMenuItem(text = { Text("创建会话分支") },
                    leadingIcon = { CodexIcon(CodexGlyph.CallSplit, null) },
                    enabled = canMutate,
                    onClick = { menu = false; onTurnAction?.invoke(item, MessageTurnAction.Fork) },
                    modifier = Modifier.testTag("message-fork-" + item.id))
                if (hasTurn && user) DropdownMenuItem(text = { Text("从此回合回退") },
                    leadingIcon = { CodexIcon(CodexGlyph.History, null, tint = Ink.red) },
                    enabled = canMutate,
                    onClick = { menu = false; onTurnAction?.invoke(item, MessageTurnAction.Revert) })
            }
        }
    }
}
