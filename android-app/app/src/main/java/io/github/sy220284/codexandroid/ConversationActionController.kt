package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Conversation commands execute on their captured thread with foreground protection. */
internal class ConversationActionController(private val vm: CodexViewModel) {
    private fun available(id: String): Boolean = with(vm) {
        if (id == activeThread.value && !running.value && !threadSwitching.value &&
            !firstThreadStarting.value && !securitySaveBusy.value && !connectionRestarter.busy.value &&
            id !in threadExecutions.value && bridge.status.value == EngineStatus.Connected &&
            globalWorkSnapshot().canStartNewTask &&
            "threadFork:$id" !in loading.value && "threadRevert:$id" !in loading.value) true
        else {
            errors.value = errors.value + ("conversation" to "请等待当前任务或连接恢复后再操作")
            false
        }
    }

    fun fork(id: String, lastTurnId: String? = null) = with(vm) {
        if (!available(id)) return
        val key = "threadFork:$id"
        val params = JSONObject().put("threadId", id).put("excludeTurns", true)
        if (lastTurnId != null) params.put("lastTurnId", lastTurnId)
        rpc(key, "thread/fork", params, after = {
            val newId = data.value[key]?.getJSONObject("thread")?.getString("id")
            if (!newId.isNullOrBlank() && activeThread.value == id) chooseThread(newId)
        })
    }

    fun revert(id: String, beforeTurnId: String) = with(vm) {
        if (!available(id)) return
        rpc("threadRevert:$id", "thread/revert",
            JSONObject().put("threadId", id).put("beforeTurnId", beforeTurnId), after = {
                if (activeThread.value == id) chooseThread(id, forceReload = true)
            })
    }

    fun compact(id: String) = start(id, "threadCompact:$id", "thread/compact/start") {
        vm.bridge.request("thread/compact/start", JSONObject().put("threadId", id))
    }

    fun review(id: String, target: JSONObject) = start(id, "review:$id", "review/start") {
        vm.bridge.request("review/start", JSONObject().put("threadId", id)
            .put("delivery", "inline").put("target", target))
    }

    private fun start(id: String, key: String, method: String,
        execute: suspend () -> JSONObject) = with(vm) {
        if (!available(id) || key in loading.value) return
        loading.value = loading.value + key
        data.value = data.value - key
        errors.value = errors.value - key - "conversation"
        threadFailures.value = threadFailures.value - id
        threadExecutions.value = ThreadExecutionPolicy.reserve(threadExecutions.value, id, emptyList())
        threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value, id, ThreadActivity.Running)
        running.value = true
        viewModelScope.launch {
            var dispatched = false
            try {
                CodexForegroundService.startAndAwaitReady(getApplication<Application>())
                dispatched = true
                val response = execute()
                if (method == "review/start") {
                    val turn = response.getJSONObject("turn")
                    require(response.getString("reviewThreadId") == id) { "审查响应所属会话不一致" }
                    if (id in threadExecutions.value && turn.optString("status") == "inProgress") {
                        threadExecutions.value = ThreadExecutionPolicy.started(threadExecutions.value, id, turn.getString("id"))
                        if (activeThread.value == id) activeTurn.value = turn.getString("id")
                    }
                }
                data.value = data.value + (key to response)
                actionFeedback.emit(ActionFeedbackCopy.success(method, response))
            } catch (failure: Exception) {
                val reason = failure.message ?: "会话操作未成功"
                val definite = !dispatched || FirstTurnRecoveryPolicy.explicitlyRejected(failure)
                if (definite) {
                    threadExecutions.value = ThreadExecutionPolicy.finish(threadExecutions.value, id)
                    threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value, id, ThreadActivity.Failed)
                    if (activeThread.value == id) { running.value = false; activeTurn.value = null }
                }
                errors.value = errors.value + (key to reason)
                if (activeThread.value == id) errors.value = errors.value + ("conversation" to reason)
                actionFeedback.emit(ActionFeedbackCopy.failure(method, reason))
            } finally {
                loading.value = loading.value - key
                syncRecentThreads()
            }
        }
        Unit
    }
}
