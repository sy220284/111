package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Conversation commands execute on their captured thread with foreground protection. */
internal class ConversationActionController(private val vm: CodexViewModel) {
    private fun available(id: String): Boolean = with(vm) {
        if (id == activeThread.value && !running.value && !threadSwitching.value &&
            !firstThreadStarting.value && !loadingEarlier.value && !timelineCompacting && !securitySaveBusy.value && !connectionRestarter.busy.value &&
            id !in threadExecutions.value && bridge.status.value == EngineStatus.Connected &&
            globalWorkSnapshot().canStartNewTask &&
            "threadFork:$id" !in loading.value && "threadRevert:$id" !in loading.value) true
        else {
            errors.value = errors.value + ("conversation" to "请等待当前任务或连接恢复后再操作")
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
        loading.value = loading.value + key
        errors.value = errors.value - "conversation" - key
        viewModelScope.launch {
            var forkId: String? = null
            try {
                val input = firstUserInput(id, turnId)
                val images = validateReplayInput(input)
                check(activeThread.value == id && !running.value && !threadSwitching.value) { "当前会话已变化，请重新选择消息" }
                val fork = bridge.request("thread/fork", JSONObject().put("threadId", id)
                    .put("lastTurnId", turnId).put("excludeTurns", true))
                val target = fork.getJSONObject("thread").getString("id")
                check(target.isNotBlank() && target != id) { "内核未返回独立分支" }
                forkId = target
                // Revert only the new fork, using its own actual final turn ID.
                val last = bridge.request("thread/turns/list", JSONObject().put("threadId", target)
                    .put("limit", 1).put("sortDirection", "desc")).getJSONArray("data").getJSONObject(0).getString("id")
                bridge.request("thread/revert", JSONObject().put("threadId", target).put("beforeTurnId", last))
                if (activeThread.value != id || threadSwitching.value) {
                    actionFeedback.emit(ActionFeedbackReceipt("已保留独立分支，请从侧栏打开后继续"))
                } else chooseThread(target, onOpened = {
                    viewModelScope.launch {
                        threadSwitching.first { !it }
                        if (activeThread.value == target) {
                            val cwd = activeWorkspace.value
                            start(target, "retry:$target", "turn/start", images, prepare = {
                                val config = bridge.request("config/read", JSONObject().put("cwd", cwd))
                                val params = JSONObject().put("threadId", target).put("input", input)
                                    .put("clientUserMessageId", UUID.randomUUID().toString())
                                ComposerPermissionPolicy.apply(params, ComposerPermissionPolicy.selected(config))
                            }) { params -> bridge.request("turn/start", params) }
                        } else actionFeedback.emit(ActionFeedbackReceipt("已创建分支，但会话已切换；尚未重新提交指令", isError = true))
                    }
                })
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

    private suspend fun firstUserInput(id: String, turnId: String): JSONArray {
        var cursor: String? = null
        do {
            val params = JSONObject().put("threadId", id).put("turnId", turnId)
                .put("sortDirection", "asc").put("limit", 100)
            cursor?.let { params.put("cursor", it) }
            val page = vm.bridge.request("thread/items/list", params)
            val rows = page.getJSONArray("data")
            for (index in 0 until rows.length()) {
                val item = rows.getJSONObject(index).getJSONObject("item")
                if (item.optString("type") == "userMessage") return item.getJSONArray("content")
            }
            cursor = historyCursor(page)
        } while (cursor != null)
        error("该回合没有可重新提交的原始指令")
    }

    private fun validateReplayInput(input: JSONArray): List<String> {
        require(input.length() > 0) { "原始指令为空" }
        val images = mutableListOf<String>()
        for (index in 0 until input.length()) {
            val part = input.getJSONObject(index)
            when (part.getString("type")) {
                "text", "image", "audio", "mention" -> Unit
                "localImage", "localAudio", "skill" -> {
                    val path = part.getString("path")
                    require(File(path).isAbsolute && File(path).isFile) { "原始附件或引用已失效，请重新添加后发送" }
                    if (part.getString("type") == "localImage") images += path
                }
                else -> error("原始指令包含当前版本无法重放的内容")
            }
        }
        return images
    }

    fun revert(id: String, beforeTurnId: String) = with(vm) {
        if (!available(id)) return@with
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

    private fun start(id: String, key: String, method: String, images: List<String> = emptyList(),
        prepare: suspend () -> JSONObject = { JSONObject() },
        execute: suspend (JSONObject) -> JSONObject) = with(vm) {
        if (!available(id) || key in loading.value) return@with
        loading.value = loading.value + key
        data.value = data.value - key
        errors.value = errors.value - key - "conversation"
        threadFailures.value = threadFailures.value - id
        threadExecutions.value = ThreadExecutionPolicy.reserve(threadExecutions.value, id, images)
        val reservation = threadExecutions.value.getValue(id)
        threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value, id, ThreadActivity.Running)
        running.value = true
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
