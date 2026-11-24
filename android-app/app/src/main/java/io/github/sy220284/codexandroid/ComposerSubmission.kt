package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun CodexViewModel.acceptComposerDraft(threadId: String?, submitted: ComposerDraft) {
    composerDrafts.update { ThreadComposerDrafts.accepted(it, threadId, submitted.text, submitted.images, submitted.references) }
}

/** Snapshot all input once; ordinary send, queue and steer use the same typed payload. */
internal fun CodexViewModel.submitComposerDraft(draft: ComposerDraft, followUp: String) {
    if (!draft.hasInput) return
    if (securitySaveBusy.value) {
        errors.value = errors.value + ("conversation" to "正在保存权限，请完成后再发送指令")
        return
    }
    if (running.value) {
        val owner = activeThread.value ?: return
        submitRunningComposer(draft, followUp) { acceptComposerDraft(owner, draft) }
    } else sendPrompt(draft.text, draft.images, draft.references) { acceptComposerDraft(it, draft) }
}

internal fun CodexViewModel.submitRunningComposer(draft: ComposerDraft, followUp: String, accepted: () -> Unit) {
    val thread = activeThread.value ?: return
    val turn = activeTurn.value
    val queue = followUp == "queue"
    val key = if (queue) "queueWrite" else "steer"
    if (!running.value || threadSwitching.value || steerBusy.value || "queueWrite" in loading.value ||
        !draft.hasInput || (!queue && turn.isNullOrBlank())) return
    if (DataTransferMutationGate.refuse(this, key, if (queue) "thread/queue/add" else "turn/steer")) return
    if (connectionRestarter.busy.value || bridge.status.value != EngineStatus.Connected) {
        errors.value = errors.value + (key to "连接尚未就绪，输入已保留")
        return
    }
    if (queue) loading.value = loading.value + key else steerBusy.value = true
    pendingComposerImages.value = pendingComposerImages.value + draft.images
    viewModelScope.launch {
        try {
            ComposerInputPolicy.validate(draft.images, draft.references, LocalImageAttachments.directory(getApplication()), File(workingDirectory))
            if (queue) {
                // Native queue snapshots local images before acknowledging enqueue.
                bridge.request("thread/queue/add", JSONObject().put("threadId", thread)
                    .put("clientUserMessageId", UUID.randomUUID().toString())
                    .put("input", TurnRequestContract.input(draft.text, draft.images, draft.references)))
                accepted()
                refresh("queue:$thread", "thread/queue/list", JSONObject().put("threadId", thread).put("limit", 100))
            } else {
                require(!turn.isNullOrBlank()) { "当前没有可补充的运行任务" }
                // Keep image ownership for the live turn, including when the user switches away.
                threadExecutions.update { executions ->
                    val current = executions[thread] ?: error("当前任务已经结束")
                    check(current.turnId == turn) { "当前运行轮次已经变化" }
                    executions + (thread to current.copy(images = (current.images + draft.images).distinct()))
                }
                bridge.request("turn/steer", TurnRequestContract.steer(thread, turn, draft.text, draft.images, draft.references))
                accepted()
                if (activeThread.value == thread && activeTurn.value == turn) {
                    val summary = draft.text.trim().ifBlank { "附件与引用" }
                    timeline.value = timeline.value + TimelineItem("steer-" + System.nanoTime(), "user", "补充指令：" + summary)
                }
            }
            errors.value = errors.value - key
        } catch (failure: Exception) {
            errors.value = errors.value + (key to (failure.message ?: "指令未被接受，输入已保留"))
        } finally {
            pendingComposerImages.value = pendingComposerImages.value - draft.images.toSet()
            if (queue) loading.value = loading.value - key else steerBusy.value = false
        }
    }
}
