package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.update

/** Drafts reuse persisted composer storage, under a separate question identity. */
internal class AsyncReplyReceipts {
    val accepted = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val uncertain = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
}

internal fun CodexViewModel.submitAsyncReply(thread: String, question: AsyncReplyQuestion,
    answer: String, queue: Boolean) {
    val key = AsyncReplyContract.key(thread, question)
    val receiptKey = AsyncReplyContract.receiptKey(thread, question)
    if (composerDrafts.value[receiptKey]?.text == "unverified") return
    if (answer.isBlank() || answer.length > 16384 || activeThread.value != thread ||
        activeTurn.value != question.turnId || !running.value || threadSwitching.value ||
        securitySaveBusy.value || steerBusy.value || "queueWrite" in loading.value ||
        "interrupt" in loading.value || key in asyncReplyReceipts.accepted.value ||
        key in asyncReplyReceipts.uncertain.value) return
    val current = timeline.value.firstOrNull { it.id == question.messageId && it.turnId == question.turnId } ?: return
    if (question !in AsyncReplyContract.questions(current)) return
    if (question.id in AsyncReplyContract.answeredFromTimeline(timeline.value) ||
        question.id in AsyncReplyContract.answeredFromQueue(data.value["queue:$thread"])) return
    val payload = AsyncReplyContract.encode(question, answer)
    submitRunningComposer(ComposerDraft(text = payload), if (queue) "queue" else "steer",
        onSettled = { result ->
            if (result == null) {
                asyncReplyReceipts.uncertain.update { it + key }
                editComposerText(receiptKey, "unverified")
            }
        }) {
        asyncReplyReceipts.accepted.update { it + key }
        asyncReplyReceipts.uncertain.update { it - key }
        composerDrafts.update { ThreadComposerDrafts.accepted(it, key, answer, emptyList()) - receiptKey }
    }
}

@Composable
internal fun NativeAsyncReplyCard(vm: CodexViewModel, thread: String, question: AsyncReplyQuestion,
    answered: Boolean, active: Boolean, enabled: Boolean) {
    val key = AsyncReplyContract.key(thread, question)
    val receiptKey = AsyncReplyContract.receiptKey(thread, question)
    val drafts by vm.composerDrafts.collectEntries(key, receiptKey)
    var confirmRetry by remember(key) { mutableStateOf(false) }
    val accepted by vm.asyncReplyReceipts.accepted.collectAsState()
    val uncertain by vm.asyncReplyReceipts.uncertain.collectAsState()
    val draft = drafts[key]?.text.orEmpty()
    val resolved = answered || key in accepted
    val unknown = !resolved && (key in uncertain || drafts[receiptKey]?.text == "unverified")
    val copy = rememberConversationCopy()
    val editable = active && enabled && !resolved && !unknown
    Surface(Modifier.fillMaxWidth().testTag("async-reply-question"),
        shape = RoundedCornerShape(12.dp), color = Ink.recessed) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要你的回答", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            Text(question.title, style = MaterialTheme.typography.titleMedium)
            if (resolved) Text("已提交回答", color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            else if (unknown) Text("发送结果待核实，请查看当前会话与队列。原回答已保留。",
                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            else if (!active) Text("原任务已结束；未提交的回答仍可复制。", color = Ink.muted,
                style = MaterialTheme.typography.bodySmall)
            if (!resolved && active && !unknown) {
                question.options.forEach { option ->
                    CodexFilterChip(selected = draft == option, onClick = { vm.editComposerText(key, option) },
                        enabled = editable, label = { Text(option) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
                ActionQuestionRow(draft, { vm.editComposerText(key, it.take(16384)) },
                    "自定义回答", singleLine = false, enabled = editable, replyTag = "async-reply-custom")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodexSecondaryButton(onClick = { vm.submitAsyncReply(thread, question, draft, true) },
                        enabled = editable && draft.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("加入队列") }
                    CodexSecondaryButton(onClick = { vm.submitAsyncReply(thread, question, draft, false) },
                        enabled = editable && draft.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("async-reply-submit")) { Text("提交回答") }
                }
            } else if (!resolved && draft.isNotBlank()) {
                Text(draft, style = MaterialTheme.typography.bodyMedium)
                CodexSecondaryButton(onClick = { copy(draft, CopyContentFormat.Literal) }) { Text("复制未发送回答") }
                if (unknown && active && enabled) {
                    CodexSecondaryButton(onClick = { vm.chooseThread(thread, forceReload = true) }) { Text("刷新会话以核对回答") }
                    CodexSecondaryButton(onClick = { confirmRetry = true }) { Text("确认未送达后重试") }
                }
            }
        }
    }
    if (confirmRetry) CodexConfirmDialog(title = "核对发送结果",
        message = "请先核对会话和任务队列，确认原回答未被接收。解除待核实状态后可重新提交，避免重复回答。",
        confirmLabel = "确认未送达", destructive = false,
        onConfirm = {
            confirmRetry = false
            vm.asyncReplyReceipts.uncertain.update { it - key }
            vm.editComposerText(receiptKey, "")
        }, onDismiss = { confirmRetry = false })
}
