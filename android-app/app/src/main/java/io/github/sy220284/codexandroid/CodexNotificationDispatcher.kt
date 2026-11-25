package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Central entrypoint for app-server notifications. Global task lifecycle events
 * are handled before the visible-thread filter so background sessions are not
 * discarded. A separate file keeps orchestration out of the UI ViewModel.
 */
internal object CodexNotificationDispatcher {
    suspend fun dispatch(vm: CodexViewModel, message: JSONObject) {
        with(vm) {
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val threadId = params.optString("threadId")
        if (method == "local/unsupportedServerRequest") return HostRequestFeedback.report(params, errors)
        // MCP registration and OAuth are agent/runtime responsibilities.
        // Refresh only live service availability after their lifecycle events.
        if (method == "mcpServer/oauthLogin/completed") {
            refreshMcpServices()
            return
        }
        if (method == "mcpServer/startupStatus/updated") {
            if (params.optString("name") == ModelPreviewMcpServer.NAME) {
                modelPreviews.startup(message.optLong(CORE_SESSION_FIELD, -1L), params)
                return
            }
            mcpCatalog.recordStartup(message.optLong(CORE_SESSION_FIELD, -1L), params)
            val targetThread = params.optString("threadId").takeIf { it.isNotBlank() }
            if ((targetThread == null || targetThread == activeThread.value) &&
                params.optString("status") in setOf("ready", "failed", "cancelled")) {
                refreshMcpServices()
            }
            return
        }
        if (method == "app/list/updated") {
            extensions.appDirectoryChanged(params)
            return
        }
        if (method == "account/rateLimits/updated") {
            accountMetrics.refresh(setOf("limits"))
            return
        }
        if (method == "account/login/completed") {
            val result = AccountLoginContract.completion(params, activeLoginId.value)
            if (result != null) {
                activeLoginId.value = ""
                accountLoginUrl.value = ""
                deviceLoginCode.value = ""
                deviceLoginUrl.value = ""
                if (result.success) errors.value = errors.value - "account"
                else errors.value = errors.value + ("account" to
                    result.error.ifBlank { "登录未成功，请重新开始授权" })
                refresh("account", "account/read")
                if (result.success) {
                    catalogWarmup.accountChanged()
                    modelPreviews.clear()
                    accountMetrics.accountChanged()
                    extensions.invalidate()
                    refresh("mcp", "mcpServerStatus/list",
                        CatalogRequestContract.mcpStatus(activeThread.value))
                }
            }
        } else if (method == "account/updated") {
            refresh("account", "account/read")
            catalogWarmup.accountChanged()
            mcpCatalog.clearStartup()
            modelPreviews.clear()
            accountMetrics.accountChanged()
            extensions.invalidate()
            refreshMcpServices()
        }
        if (method == "project/changed") {
            val changedId = params.optString("projectId")
            if (params.optString("changeType") == "deleted" &&
                changedId == activeProjectId.value) activeProjectId.value = null
            projectCatalogRevision.value += 1
            return
        }
        if (method == "thread/readState/changed" && threadId.isNotBlank()) {
            params.optJSONObject("readState")?.let { sessions.receipt(threadId, it) }
            return
        }
        if (method in setOf("thread/name/updated", "thread/section/changed", "thread/closed")) sessions.refreshChanged(threadId)
        if (method == "thread/status/changed" && threadId.isNotBlank()) {
            threadActivities.value = ThreadActivityPolicy.updated(threadActivities.value,
                threadId, ThreadActivityPolicy.parse(params.optJSONObject("status")))
            return
        }
        if (method == "thread/project/updated") {
            if (threadId == activeThread.value) {
                activeProjectId.value = params.optString("projectId")
                    .takeIf { it.isNotBlank() }
            }
            sessions.refreshChanged(threadId)
            projectCatalogRevision.value += 1
            return
        }
        if (method == "command/exec/outputDelta") {
            val active = terminalSession.value
            if (active.processId.isNotEmpty() &&
                params.optString("processId") == active.processId && active.running) {
                try {
                    terminalSession.value = active.append(params)
                    terminalTranscript.appendNotification(params)
                } catch (failure: Exception) {
                    terminalSession.value = active.copy(error =
                        "终端输出解码失败：" + (failure.message ?: "非法输出"))
                }
            }
            return
        }
        if (method in setOf("thread/archived", "thread/unarchived", "thread/deleted")) {
            threadOrganization.observe(method, threadId)
            sessions.refreshChanged(threadId)
        } else if (method == "thread/started") {
            sessions.refreshChanged(threadId)
        }
        if (method == "thread/deleted") {
            modelPreviews.removeThread(threadId)
            answerVersions.remove(threadId)
            sessions.removePin(threadId)
            liveTimelines.remove(threadId)
            queuePausedThreads.value = queuePausedThreads.value - threadId
        }
        // Apply turn lifecycle to every thread before filtering display events to
        // the visible timeline. An offscreen task must still finish and free images.
        if (threadId.isNotBlank() && method == "turn/started") {
            queuePausedThreads.value = queuePausedThreads.value - threadId
            val turnId = params.optJSONObject("turn")?.optString("id")
            if (!turnId.isNullOrBlank()) answerVersions.bind(threadId, turnId)
            threadExecutions.value = ThreadExecutionPolicy.started(
                threadExecutions.value, threadId, turnId)
            threadActivities.value = ThreadActivityPolicy.updated(
                threadActivities.value, threadId, ThreadActivity.Running)
            if (threadId == activeThread.value) {
                conversationState.update { it.copy(turnId = threadExecutions.value[threadId]?.turnId, running = true) }
            }
        }
        if (threadId.isNotBlank() && method == "turn/started") sessions.refreshChanged(threadId)
        if (threadId.isNotBlank() && method == "turn/completed") {
            val completedId = params.optJSONObject("turn")?.optString("id")
            val execution = threadExecutions.value[threadId]
            val validCompletion = execution?.turnId.isNullOrBlank() ||
                completedId.isNullOrBlank() || execution?.turnId == completedId
            if (!validCompletion) return
            threadExecutions.value = ThreadExecutionPolicy.finish(
                threadExecutions.value, threadId)
            val turnStatus = params.optJSONObject("turn")?.optString("status")
            if (turnStatus == "interrupted") queuePausedThreads.value = queuePausedThreads.value + threadId
            val completed = turnStatus == "completed"
            if (!completed) {
                val reason = params.optJSONObject("turn")?.optJSONObject("error")
                    ?.optString("message").orEmpty().ifBlank { "任务已中断或失败" }
                threadFailures.value = ThreadExecutionPolicy.failureUpdated(
                    threadFailures.value, threadId, reason)
            } else threadFailures.value = threadFailures.value - threadId
            threadActivities.value = ThreadActivityPolicy.updated(
                threadActivities.value, threadId,
                if (completed) ThreadActivity.Idle else ThreadActivity.Failed)
            if (threadId == activeThread.value) {
                conversationState.update { it.copy(running = false, turnId = null) }
            }
            sessions.refreshChanged(threadId)
            accountMetrics.refresh()
            val finishedImages = execution?.images.orEmpty()
            if (finishedImages.isNotEmpty()) viewModelScope.launch {
                for (path in finishedImages) {
                    try {
                        if (AttachmentRetentionPolicy.canRelease(
                                path, composerDrafts.value.values,
                                threadExecutions.value.values.flatMap { it.images } + pendingComposerImages.value
                            )) LocalImageAttachments.release(getApplication(), path)
                    } catch (failure: Exception) {
                        errors.value = errors.value + ("attachments" to
                            (failure.message ?: "无法清理已完成任务的图片"))
                    }
                }
            }
        }
        if (method == "thread/tokenUsage/updated" && threadId.isNotBlank()) {
            params.optJSONObject("tokenUsage")?.let { usage ->
                data.value = data.value + ("tokenUsage:$threadId" to usage)
            }
            return
        }
        // Keep thread-owned goal and queue UI synchronized with agent-side mutations.
        // These notifications are emitted even when changes do not originate in the UI.
        if (threadId.isNotBlank() && threadId == activeThread.value) {
            when (method) {
                "thread/goal/updated" -> {
                    data.value = data.value + ("goal:$threadId" to JSONObject()
                        .put("goal", params.optJSONObject("goal") ?: JSONObject.NULL))
                    return
                }
                "thread/goal/cleared" -> {
                    data.value = data.value + ("goal:$threadId" to JSONObject()
                        .put("goal", JSONObject.NULL))
                    return
                }
                "thread/queue/changed" -> {
                    refresh("queue:$threadId", "thread/queue/list",
                        JSONObject().put("threadId", threadId).put("limit", 100))
                    return
                }
            }
        }
        if (threadId.isNotBlank() && (threadId == hydratingThread || threadId != activeThread.value) &&
            method in TimelineEventReducer.methods) {
            liveTimelines.event(threadId, method, params, threadExecutions.value.keys)
            return
        }
        if (threadId.isNotBlank() && threadId != activeThread.value) return
        when (method) {
            in TimelineEventReducer.methods - "turn/completed" ->
                timeline.value = TimelineEventReducer.reduce(method, params, timeline.value)
            "turn/diff/updated" -> latestDiff.value = params.optString("diff")
            "turn/completed" -> timeline.value = TimelineEventReducer.reduce(method, params, timeline.value)
        }
        if (!threadSwitching.value && !running.value && !loadingEarlier.value && !timelineCompacting && method in TimelineEventReducer.methods && timeline.value.size > TimelineWindowStore.COMPACT_AT) {
            val epoch = timelineEpoch
            val owner = activeThread.value
            val snapshot = timeline.value
            timelineCompacting = true
            try {
                val compacted = timelineWindow.compact(owner, snapshot, epoch)
                if (timelineEpoch == epoch && activeThread.value == owner) {
                    // Concurrent send may append a pending user item while disk I/O runs.
                    val retained = timeline.value.drop(snapshot.size - compacted.size)
                    timeline.value = retained
                }
            } catch (failure: Exception) {
                errors.value = errors.value + ("timeline" to
                    (failure.message ?: "会话历史无法暂存，当前记录仍保留在内存"))
            } finally { timelineCompacting = false }
        }
        }
    }
}
