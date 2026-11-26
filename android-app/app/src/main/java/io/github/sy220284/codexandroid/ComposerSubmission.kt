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
    expectedThread: String?, expectedTurn: String?, expectedRunning: Boolean, expectedOwner: String) {
    if (!draft.hasInput) return
    if (composerOwner(activeThread.value) != expectedOwner ||
        activeThread.value != expectedThread || running.value != expectedRunning ||
        (expectedRunning && activeTurn.value != expectedTurn)) {
        val key = if (!expectedRunning) "conversation" else if (followUp == "queue") "queueWrite" else "steer"
        errors.value = errors.value + (key to "任务状态已变化，指令仍保留，请确认发送方式后重试")
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

internal fun CodexViewModel.submitRunningComposer(draft: ComposerDraft, followUp: String,
    onSettled: (Boolean?) -> Unit = {}, accepted: () -> Unit) {
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
    val epoch = bridge.connectedSession.value ?: return
    val accountRevision = catalogWarmup.accountRevision.value
    val permit = { catalogWarmup.accountRevision.value == accountRevision }
    if (queue) loading.value = loading.value + key else steerBusy.value = true
    errors.value = errors.value - key
    pendingComposerImages.value = pendingComposerImages.value + draft.images
    viewModelScope.launch {
        var dispatched = false
        var outcome: Boolean? = false
        try {
            check(bridge.connectedSession.value == epoch && permit()) { "连接或账号已变化，原输入已保留" }
            remoteBindings.forThread(thread)?.let {
                it.requireTextOnly(draft.images, draft.references)
                ensureRemoteThread(thread, it, running = true, expectedSession = epoch)
            }
            ComposerInputPolicy.validate(draft.images, draft.references, LocalImageAttachments.directory(getApplication()), File(workspace))
            if (queue) {
                check(threadExecutions.value[thread]?.turnId == turn && thread in threadExecutions.value) {
                    "当前任务已变化，请重新确认发送方式；原指令已保留"
                }
                // Native queue snapshots local images before acknowledging enqueue.
                val params = JSONObject().put("threadId", thread)
                    .put("clientUserMessageId", UUID.randomUUID().toString())
                    .put("input", TurnRequestContract.input(draft.text, draft.images, draft.references))
                dispatched = true
                outcome = null
                bridge.request("thread/queue/add", params, expectedSession = epoch, sendPermit = permit)
                outcome = true
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
                val params = TurnRequestContract.steer(thread, turn, draft.text, draft.images, draft.references)
                    .put("clientUserMessageId", UUID.randomUUID().toString())
                LocalImageAttachments.preserveForHistory(getApplication(), draft.images)
                check(threadExecutions.value[thread]?.turnId == turn) { "当前运行回合已变化，原指令已保留" }
                dispatched = true
                outcome = null
                bridge.request("turn/steer", params, expectedSession = epoch, sendPermit = permit)
                outcome = true
                accepted()
                actionFeedback.tryEmit(ActionFeedbackReceipt("引导指令已发送"))
            }
            errors.value = errors.value - key
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            val method = if (queue) "thread/queue/add" else "turn/steer"
            if (failure is RpcServerRejection && failure.method == method) outcome = false
            val reason = failure.message ?: "发送失败"
            val message = if (dispatched && !(failure is RpcServerRejection && failure.method == method))
                "$reason；发送结果尚未确认，原输入已保留，请先核对当前消息或队列再重试"
            else "$reason；原输入已保留"
            errors.value = errors.value + (key to message)
            if (queue && dispatched)
                refresh("queue:$thread", "thread/queue/list", JSONObject().put("threadId", thread).put("limit", 100))
        } finally {
            pendingComposerImages.value = pendingComposerImages.value - draft.images.toSet()
            if (queue) loading.value = loading.value - key else steerBusy.value = false
            onSettled(outcome)
        }
    }
}

/** Native queue start is allowed only when the selected thread is idle.
 * Do not interrupt the running turn to simulate immediate execution. */
internal fun CodexViewModel.startQueuedComposer(threadId: String, queuedId: String? = null) {
    val key = "queueWrite"
    if (activeThread.value != threadId || running.value || threadId in threadExecutions.value || answerFamilyBusy(threadId) ||
        firstThreadStarting.value || threadSwitching.value || steerBusy.value ||
        key in loading.value || securitySaveBusy.value || connectionRestarter.busy.value ||
        bridge.status.value != EngineStatus.Connected) {
        errors.value = errors.value + (key to "当前会话尚未空闲或连接未就绪，请稍后执行队列")
        return
    }
    if (DataTransferMutationGate.refuse(this, key, "thread/queue/start")) return
    val params = JSONObject().put("threadId", threadId)
    if (queuedId != null) {
        require(queuedId.isNotBlank()) { "缺少排队消息标识" }
        params.put("queuedSubmissionId", queuedId)
    }
    // Reserve before dispatch, exactly like an ordinary turn. Native queue input
    // already owns its attachments; no duplicate local image references are needed.
    threadExecutions.value = ThreadExecutionPolicy.reserve(threadExecutions.value, threadId, emptyList())
    val reservation = threadExecutions.value.getValue(threadId)
    loading.value = loading.value + key
    errors.value = errors.value - key
    viewModelScope.launch {
        var dispatched = false
        try {
            CodexForegroundService.startAndAwaitReady(getApplication())
            check(threadExecutions.value[threadId] === reservation &&
                bridge.status.value == EngineStatus.Connected && !connectionRestarter.busy.value) {
                "运行核心状态已变化，请重新启动队列"
            }
            remoteBindings.forThread(threadId)?.let { ensureRemoteThread(threadId, it) }
            dispatched = true
            val response = bridge.request("thread/queue/start", params)
            val turnId = response.optJSONObject("turn")?.optString("id").orEmpty()
            require(turnId.isNotBlank()) { "核心未返回有效执行回合，请先核对队列" }
            // The started/completed events may have been processed before the response.
            // Never overwrite a newer turn or resurrect an already completed one.
            if (threadExecutions.value[threadId] === reservation) {
                threadExecutions.value = ThreadExecutionPolicy.started(threadExecutions.value, threadId, turnId)
                threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value, threadId, ThreadActivity.Running)
                if (activeThread.value == threadId) {
                    conversationState.update { it.copy(turnId = turnId, running = true) }
                }
            }
            if (threadExecutions.value[threadId]?.turnId == turnId) {
                queuePausedThreads.value = queuePausedThreads.value - threadId
                threadFailures.value = threadFailures.value - threadId
            }
            actionFeedback.tryEmit(ActionFeedbackReceipt("已开始执行后续指令"))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            val definite = !dispatched || failure is RpcServerRejection && failure.method == "thread/queue/start"
            if (threadExecutions.value[threadId] === reservation) {
                if (definite) {
                    threadExecutions.value = ThreadExecutionPolicy.finish(threadExecutions.value, threadId)
                    if (activeThread.value == threadId) { conversationState.update { it.copy(running = false, turnId = null) } }
                } else {
                    // An ambiguous response cannot safely authorize another start.
                    if (activeThread.value == threadId) running.value = true
                }
            }
            val reason = failure.message ?: "无法启动后续指令"
            val message = if (definite) reason else "$reason；启动结果尚未确认，请核对队列和运行状态，避免重复执行"
            errors.value = errors.value + (key to message)
            actionFeedback.tryEmit(ActionFeedbackCopy.failure("thread/queue/start", message))
        } finally {
            loading.value = loading.value - key
            if (bridge.status.value == EngineStatus.Connected) {
                refresh("queue:$threadId", "thread/queue/list", JSONObject().put("threadId", threadId).put("limit", 100))
                sessions.refreshChanged(threadId)
            }
        }
    }
}
