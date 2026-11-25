package io.github.sy220284.codexandroid

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun CodexViewModel.selectConversation(id: String, forceReload: Boolean, onOpened: (() -> Unit)?, workspaceTasks: WorkspaceTaskOperations, workspacePreferences: SharedPreferences) {
    if (!globalWorkSnapshot().canStartNewTask) {
        errors.value = errors.value + ("conversation" to "本机备份或恢复正在进行，完成后再切换会话")
        return
    }
    // Serialize resume requests: multiple rapid taps must not race the active session.
    if (threadSwitching.value || loadingEarlier.value || timelineCompacting) return
    if (firstThreadStarting.value) {
        errors.value = errors.value + ("conversation" to "正在建立新会话，请在会话创建后切换")
        return
    }
    if (!forceReload && id.isNotBlank() && id == activeThread.value) {
        sessions.markRead(id, false)
        onOpened?.invoke()
        return
    }
    if (!forceReload && !ThreadDisplayPolicy.canOpenThread(activeThread.value, id, running.value)) return
    val epoch = bridge.connectedSession.value
    val accountRevision = catalogWarmup.accountRevision.value
    threadSwitching.value = true
    activeThread.value?.let { current ->
        if (running.value) liveTimelines.capture(current, activeTurn.value, timeline.value)
    }
    if (forceReload && !running.value) liveTimelines.remove(id)
    hydratingThread = id
    errors.value = errors.value - "conversation"
    viewModelScope.launch {
        try {
            val session = requireNotNull(epoch) { "运行核心尚未连接" }
            val history = bridge.request("thread/read", JSONObject().put("threadId", id), expectedSession = session)
            val nativeThread = history.getJSONObject("thread")
            val remote = remoteBindings.forThread(id)
            check(remote != null || !RemoteExecutionBinding.hasRemote(nativeThread)) { "远端会话缺少本机连接记录，请先核对执行环境" }
            // Local history reads do not need to reach the remote executor. Resume and
            // verify the remote binding only when a user actually starts execution.
            val threadDirectory = if (remote != null) WorkspaceSelectionContract.validate(java.io.File(workingDirectory), java.io.File(workingDirectory))
                else workspaceTasks.resumeDirectory(id, nativeThread.getString("cwd"),
                    nativeThread.optString("projectId").takeUnless { value -> value == "null" }.orEmpty(), null, session)
            val page = bridge.request("thread/items/list", JSONObject().put("threadId", id)
                .put("sortDirection", "desc").put("limit", 120), expectedSession = session)
            check(bridge.connectedSession.value == session && catalogWarmup.accountRevision.value == accountRevision) { "会话连接已变化，请重新打开" }
            val restored = readHistoryPage(page)
            val turns = TimelineEventReducer.historyTurns(restored)
            val newest = timelineWindow.replace(id, restored, ++timelineEpoch)
            workspacePreferences.edit()
                .putString("activeDirectory", threadDirectory.absolutePath).apply()
            restoreComposerOptions(id)
            val execution = threadExecutions.value[id]
            errors.value = errors.value - "conversation"
            threadFailures.value[id]?.let { reason ->
                errors.value = errors.value + ("conversation" to reason)
            }
            olderServerCursor.value = historyCursor(page)
            conversationState.value = ActiveConversationContext(threadDirectory.absolutePath,
                history.getJSONObject("thread").optString("projectId").takeIf { it.isNotBlank() },
                id, execution?.turnId, execution != null,
                IndexedTimeline.from(liveTimelines.restore(id, newest)), ThreadDiffHistory.latest(turns),
                ComposerProviderScope.provider(history.getJSONObject("thread").optString("modelProvider")), remote)
            answerVersions.select(id); sessions.markRead(id, false, history.optJSONObject("readState"))
            onOpened?.invoke()
        } catch (failure: Exception) {
            errors.value = errors.value + ("conversation" to (failure.message ?: "无法打开会话"))
        } finally {
            hydratingThread = null
            threadSwitching.value = false
        }
    }
}
