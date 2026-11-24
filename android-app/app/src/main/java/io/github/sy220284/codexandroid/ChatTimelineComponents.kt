package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimelineStepCard(item: TimelineItem) {
    val user = item.kind in setOf("user", "userMessage")
    val assistant = item.kind in setOf("agent", "agentMessage")
    val failure = item.kind == "error" ||
        item.status.contains("failed", ignoreCase = true)
    val active = item.status.contains("progress", ignoreCase = true) ||
        item.status.contains("stream", ignoreCase = true)
    val tone = when {
        failure -> Ink.red
        active -> Ink.copper
        user -> Ink.copper
        item.status.equals("completed", true) -> Ink.jade
        else -> Ink.muted
    }
    val title = PresentationPolicy.timelineKind(item.kind)
    val icon = when (item.kind) {
        "user" -> CodexGlyph.PersonOutline
        "commandExecution" -> CodexGlyph.Terminal
        "fileChange" -> CodexGlyph.Diff
        "mcpToolCall" -> CodexGlyph.Hub
        "plan" -> CodexGlyph.ViewList
        "error" -> CodexGlyph.TaskError
        else -> CodexGlyph.AutoAwesome
    }
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val canOpenMenu = item.text.isNotBlank() || item.evidence.isNotBlank()
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var readerOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var evidenceOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
    Surface(
        modifier = if (user) Modifier.fillMaxWidth(.88f).testTag("chat-user-message")
            else Modifier.fillMaxWidth().testTag(if (assistant) "chat-assistant-message" else "chat-execution-step"),
        shape = RoundedCornerShape(17.dp),
        color = if (user) Ink.parchment.copy(alpha = .055f) else Ink.canvas,
        border = when {
            failure -> BorderStroke(1.dp, Ink.red.copy(alpha = .5f))
            user || assistant -> null
            else -> BorderStroke(1.dp, Ink.border.copy(alpha = .65f))
        }
    ) {
        Row(Modifier.padding(horizontal = if (assistant) 2.dp else 12.dp, vertical = 12.dp)) {
            if (!user && !assistant) {
                CodexIcon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.labelLarge,
                        color = Ink.muted, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .testTag("timeline-header-" + item.id)
                            .combinedClickable(enabled = canOpenMenu,
                                onClick = { menuOpen = true },
                                onLongClickLabel = "打开消息操作",
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuOpen = true
                                }))
                    val statusLabel = PresentationPolicy.timelineStatus(item.status)
                    if (statusLabel.isNotEmpty()) {
                        Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = tone)
                    }
                    IconButton(onClick = { menuOpen = true }, enabled = canOpenMenu, modifier = Modifier.size(48.dp)
                        .testTag("timeline-more-" + item.id)) {
                        CodexIcon(CodexGlyph.MoreHoriz, "消息操作")
                    }
                }
                SelectionContainer {
                    Text(item.text.takeUnless { it == item.kind || PresentationPolicy.meaningful(it).isEmpty() }
                        ?: "正在处理…",
                        style = if (user || assistant) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodyMedium,
                        fontFamily = if (item.kind == "commandExecution") FontFamily.Monospace else null,
                        maxLines = if (user || assistant) Int.MAX_VALUE else 10,
                        overflow = TextOverflow.Ellipsis)
                }
                if (!user && !assistant && item.text.isNotBlank()) {
                    TextButton(onClick = { readerOpen = true }) { Text("查看完整内容") }
                }
                if (item.evidence.isNotBlank()) {
                    TextButton(onClick = { evidenceOpen = true },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        CodexIcon(CodexGlyph.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("查看执行依据")
                    }
                }
            }
        }
    }
    }
    if (menuOpen) CodexActionSheet(title = title, onDismiss = { menuOpen = false },
        actions = buildList {
            if (item.text.isNotBlank()) {
                add(CodexSheetAction(label = "复制内容", description = "复制原始文本", icon = CodexGlyph.ContentCopy,
                    onClick = { menuOpen = false; clipboard.setText(AnnotatedString(item.text)) }))
                add(CodexSheetAction(label = "全文阅读", description = "检索、定位与复制", icon = CodexGlyph.OpenInFull,
                    onClick = { menuOpen = false; readerOpen = true }))
            }
            if (item.evidence.isNotBlank()) add(CodexSheetAction(label = "查看执行依据", description = "读取原始执行记录",
                icon = CodexGlyph.Description, onClick = { menuOpen = false; evidenceOpen = true }))
        })
    if (readerOpen) FullTextReaderDialog(title, item.text) { readerOpen = false }
    if (evidenceOpen) CodexEvidenceSheet(
        title = title + " · 执行依据",
        payload = item.evidence,
        onDismiss = { evidenceOpen = false }
    )
}
