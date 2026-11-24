package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun CodexViewModel.acceptComposerDraft(threadId: String?, submitted: ComposerDraft) {
    composerDrafts.update { ThreadComposerDrafts.accepted(it, composerOwner(threadId), submitted.text, submitted.images, submitted.references) }
}

/** Snapshot all input once; ordinary send, queue and steer use the same typed payload. */
internal fun CodexViewModel.submitComposerDraft(draft: ComposerDraft, followUp: String,
    expectedThread: String?, expectedTurn: String?, expectedRunning: Boolean) {
    if (!draft.hasInput) return
    if (activeThread.value != expectedThread || running.value != expectedRunning ||
        (expectedRunning && activeTurn.value != expectedTurn)) {
        errors.value = errors.value + ("conversation" to "任务状态已变化，指令仍保留，请确认发送方式后重试")
        return
    }
    if (threadSwitching.value || firstThreadStarting.value || steerBusy.value ||
        "queueWrite" in loading.value || "interrupt" in loading.value) return
    if (securitySaveBusy.value) {
        errors.value = errors.value + ("conversation" to "正在保存权限，请完成后再发送指令")
        return
    }
    if (expectedRunning) {
        val owner = expectedThread ?: return
        submitRunningComposer(draft, followUp) { acceptComposerDraft(owner, draft) }
    } else sendPrompt(draft.text, draft.images, draft.references) { acceptComposerDraft(it, draft) }
}

internal fun CodexViewModel.submitRunningComposer(draft: ComposerDraft, followUp: String, accepted: () -> Unit) {
    val thread = activeThread.value ?: return
    val turn = activeTurn.value
    val workspace = activeWorkspace.value
    require(followUp == "queue" || followUp == "steer") { "无效发送方式" }
    val queue = followUp == "queue"
    val key = if (queue) "queueWrite" else "steer"
    if (!running.value || threadSwitching.value || steerBusy.value || "queueWrite" in loading.value ||
        "interrupt" in loading.value || !draft.hasInput || (!queue && turn.isNullOrBlank())) return
    if (DataTransferMutationGate.refuse(this, key, if (queue) "thread/queue/add" else "turn/steer")) return
    if (connectionRestarter.busy.value || bridge.status.value != EngineStatus.Connected) {
        errors.value = errors.value + (key to "连接尚未就绪，输入已保留")
        return
    }
    if (queue) loading.value = loading.value + key else steerBusy.value = true
    pendingComposerImages.value = pendingComposerImages.value + draft.images
    viewModelScope.launch {
        try {
            ComposerInputPolicy.validate(draft.images, draft.references, LocalImageAttachments.directory(getApplication()), File(workspace))
            if (queue) {
                check(threadExecutions.value[thread]?.turnId == turn && thread in threadExecutions.value) {
                    "当前任务已变化，请重新确认发送方式；原指令已保留"
                }
                // Native queue snapshots local images before acknowledging enqueue.
                bridge.request("thread/queue/add", JSONObject().put("threadId", thread)
                    .put("clientUserMessageId", UUID.randomUUID().toString())
                    .put("input", TurnRequestContract.input(draft.text, draft.images, draft.references)))
                accepted()
                actionFeedback.tryEmit(ActionFeedbackReceipt("已加入后续指令"))
                refresh("queue:$thread", "thread/queue/list", JSONObject().put("threadId", thread).put("limit", 100))
            } else {
                require(!turn.isNullOrBlank()) { "当前没有可补充的运行任务" }
                // Keep image ownership for the live turn, including when the user switches away.
                threadExecutions.update { executions ->
                    val current = executions[thread] ?: error("当前任务已经结束")
                    check(current.turnId == turn) { "当前运行轮次已经变化" }
                    executions + (thread to current.copy(images = (current.images + draft.images).distinct()))
                }
                // The native userMessage event owns the visible message. A separate local
                // steer bubble would duplicate it and could survive history reconciliation.
                bridge.request("turn/steer", TurnRequestContract.steer(thread, turn, draft.text, draft.images, draft.references)
                    .put("clientUserMessageId", UUID.randomUUID().toString()))
                accepted()
                actionFeedback.tryEmit(ActionFeedbackReceipt("引导指令已发送"))
            }
            errors.value = errors.value - key
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            errors.value = errors.value + (key to (failure.message ?: "指令未被接受，输入已保留"))
        } finally {
            pendingComposerImages.value = pendingComposerImages.value - draft.images.toSet()
            if (queue) loading.value = loading.value - key else steerBusy.value = false
        }
    }
}

/** Native queue start is allowed only when the selected thread is idle.
 * Do not interrupt the running turn to simulate immediate execution. */
internal fun CodexViewModel.startQueuedComposer(threadId: String, queuedId: String? = null,
    after: (() -> Unit)? = null) {
    val key = "queueWrite"
    if (activeThread.value != threadId || running.value || threadId in threadExecutions.value ||
        firstThreadStarting.value || threadSwitching.value || steerBusy.value ||
        key in loading.value || securitySaveBusy.value || connectionRestarter.busy.value ||
        bridge.status.value != EngineStatus.Connected) {
        errors.value = errors.value + (key to "当前会话尚未空闲或连接未就绪，请稍后执行队列")
        return
    }
    val params = JSONObject().put("threadId", threadId)
    if (queuedId != null) {
        require(queuedId.isNotBlank()) { "缺少排队消息标识" }
        params.put("queuedSubmissionId", queuedId)
    }
    rpc("queueWrite", "thread/queue/start", params, after = {
        refresh("queue:$threadId", "thread/queue/list", JSONObject().put("threadId", threadId).put("limit", 100))
        after?.invoke()
    })
}
