package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.util.UUID
import org.json.JSONObject

/** Conversation commands execute on their captured thread with foreground protection. */
internal class ConversationActionController(private val vm: CodexViewModel) {
    private fun available(id: String, foregroundOnly: Boolean = true): Boolean = with(vm) {
        val foregroundReady = id == activeThread.value && !running.value && !threadSwitching.value &&
            !firstThreadStarting.value && !loadingEarlier.value && !timelineCompacting
        if ((!foregroundOnly || foregroundReady) && (id != activeThread.value || !running.value) &&
            !securitySaveBusy.value && !connectionRestarter.busy.value &&
            id !in threadExecutions.value && bridge.status.value == EngineStatus.Connected &&
            globalWorkSnapshot().canStartNewTask &&
            "threadFork:$id" !in loading.value && "threadRevert:$id" !in loading.value) true
        else {
            val reason = "请等待当前任务或连接恢复后再操作"
            if (id == activeThread.value) errors.value = errors.value + ("conversation" to reason)
            else actionFeedback.tryEmit(ActionFeedbackReceipt(reason, isError = true))
            false
        }
    }

    fun fork(id: String, lastTurnId: String? = null) = with(vm) {
        if (!available(id)) return@with
        val key = "threadFork:$id"
        val params = JSONObject().put("threadId", id).put("excludeTurns", true)
        if (lastTurnId != null) params.put("lastTurnId", lastTurnId)
        rpc(key, "thread/fork", params, after = {
            val newId = data.value[key]?.getJSONObject("thread")?.getString("id")
            if (!newId.isNullOrBlank() && activeThread.value == id) chooseThread(newId)
        })
    }

    fun retry(id: String, turnId: String) = with(vm) {
        if (!available(id) || turnId.isBlank()) return@with
        val key = "threadFork:$id"
        val cwd = activeWorkspace.value
        loading.value = loading.value + key
        errors.value = errors.value - "conversation" - key
        viewModelScope.launch {
            var forkId: String? = null
            try {
                val replay = MessageReplayCoordinator { method, params -> bridge.request(method, params) }
                val input = replay.readInput(id, turnId)
                val images = replay.validateInput(input)
                check(activeThread.value == id && !running.value && !threadSwitching.value &&
                    !securitySaveBusy.value && !connectionRestarter.busy.value &&
                    globalWorkSnapshot().canStartNewTask) { "当前会话或权限状态已变化，请重新选择消息" }
                // The locked Rust protocol has experimental beforeTurnId; the
                // connection already negotiates experimentalApi. One native
                // fork excludes the selected turn without any history mutation.
                val target = replay.forkBefore(id, turnId)
                forkId = target
                val launched = start(target, "retry:$target", "turn/start", images, foregroundOnly = false, prepare = {
                    val config = bridge.request("config/read", JSONObject().put("cwd", cwd))
                    val params = JSONObject().put("threadId", target).put("input", input)
                        .put("clientUserMessageId", UUID.randomUUID().toString())
                    ComposerPermissionPolicy.apply(params, ComposerPermissionPolicy.selected(config))
                }) { params -> bridge.request("turn/start", params) }
                if (!launched) {
                    actionFeedback.emit(ActionFeedbackReceipt("已保留独立分支，重新回答尚未启动，请核对权限和连接后重试", isError = true))
                } else if (activeThread.value == id && !threadSwitching.value) {
                    chooseThread(target)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val reason = (failure.message ?: "重新回答未成功") +
                    if (forkId != null) "；原会话已保留，请在侧栏核对新分支" else "；原会话保持不变"
                errors.value = errors.value + (key to reason)
                if (activeThread.value == id) errors.value = errors.value + ("conversation" to reason)
                actionFeedback.emit(ActionFeedbackReceipt(reason, isError = true))
            } finally {
                loading.value = loading.value - key
                syncRecentThreads()
            }
        }
        Unit
    }

    fun revert(id: String, beforeTurnId: String) = with(vm) {
        if (!available(id)) return@with
        rpc("threadRevert:$id", "thread/revert",
            JSONObject().put("threadId", id).put("beforeTurnId", beforeTurnId), after = {
                if (activeThread.value == id) chooseThread(id, forceReload = true)
            })
    }

    fun compact(id: String) {
        start(id, "threadCompact:$id", "thread/compact/start") {
            vm.bridge.request("thread/compact/start", JSONObject().put("threadId", id))
        }
    }

    fun review(id: String, target: JSONObject) {
        start(id, "review:$id", "review/start") {
            vm.bridge.request("review/start", JSONObject().put("threadId", id)
                .put("delivery", "inline").put("target", target))
        }
    }

    private fun start(id: String, key: String, method: String, images: List<String> = emptyList(),
        foregroundOnly: Boolean = true, prepare: suspend () -> JSONObject = { JSONObject() },
        execute: suspend (JSONObject) -> JSONObject): Boolean = with(vm) {
        if (!available(id, foregroundOnly) || key in loading.value) return@with false
        loading.value = loading.value + key
        data.value = data.value - key
        errors.value = errors.value - key
        if (activeThread.value == id) errors.value = errors.value - "conversation"
        threadFailures.value = threadFailures.value - id
        threadExecutions.value = ThreadExecutionPolicy.reserve(threadExecutions.value, id, images)
        val reservation = threadExecutions.value.getValue(id)
        threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value, id, ThreadActivity.Running)
        if (activeThread.value == id) running.value = true
        viewModelScope.launch {
            var dispatched = false
            try {
                CodexForegroundService.startAndAwaitReady(getApplication<Application>())
                val params = prepare()
                dispatched = true
                val response = execute(params)
                if (method == "review/start" || method == "turn/start") {
                    val turn = response.getJSONObject("turn")
                    if (method == "review/start") require(response.getString("reviewThreadId") == id) { "审查响应所属会话不一致" }
                    require(turn.getString("id").isNotBlank()) { "内核未返回有效回合编号" }
                    if (threadExecutions.value[id] === reservation && turn.optString("status") == "inProgress") {
                        threadExecutions.value = ThreadExecutionPolicy.started(threadExecutions.value, id, turn.getString("id"))
                        if (activeThread.value == id) activeTurn.value = turn.getString("id")
                    }
                }
                data.value = data.value + (key to response)
                actionFeedback.emit(if (method == "turn/start") ActionFeedbackReceipt("已开始重新回答") else ActionFeedbackCopy.success(method, response))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val reason = failure.message ?: "会话操作未成功"
                val definite = !dispatched || FirstTurnRecoveryPolicy.explicitlyRejected(failure)
                if (definite && threadExecutions.value[id] === reservation) {
                    threadExecutions.value = ThreadExecutionPolicy.finish(threadExecutions.value, id)
                    threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value, id, ThreadActivity.Failed)
                    if (activeThread.value == id) { conversationState.update { it.copy(running = false, turnId = null) } }
                }
                val message = if (method == "turn/start") SubmissionFailurePolicy.message(failure, dispatched) else reason
                errors.value = errors.value + (key to message)
                if (method == "turn/start") threadFailures.value = ThreadExecutionPolicy.failureUpdated(threadFailures.value, id, message)
                if (activeThread.value == id) errors.value = errors.value + ("conversation" to message)
                actionFeedback.emit(ActionFeedbackCopy.failure(method, message))
            } finally {
                loading.value = loading.value - key
                syncRecentThreads()
            }
        }
        true
    }
}
