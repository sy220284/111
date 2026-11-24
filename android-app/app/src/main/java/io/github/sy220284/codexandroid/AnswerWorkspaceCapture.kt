package io.github.sy220284.codexandroid

import kotlinx.coroutines.CancellationException

/** A failed checkpoint must preserve the ordinary submission and report the limitation. */
internal suspend fun CodexViewModel.captureAnswerBaseline(clientId: String, workspace: String, thread: String?) {
    try {
        check(threadExecutions.value.keys.none { it != thread } && !terminalSession.value.running && !terminalStarting.value) {
            "其他任务或终端正在写入文件"
        }
        answerWorkspaces.checkpoint(clientId, workspace)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        actionFeedback.emit(ActionFeedbackReceipt("本轮未保存执行前文件快照：" +
            (failure.message ?: "无法建立快照") + "；以后重新回答时需确认使用当前文件", isError = true))
    }
}

/** One logical chat may own several native histories, but must not start competing mutations. */
internal fun CodexViewModel.answerFamilyBusy(thread: String): Boolean =
    answerVersions.state.value.members(thread).any { id ->
        "threadFork:$id" in loading.value || "threadRevert:$id" in loading.value ||
            "retry:$id" in loading.value || (id != thread && id in threadExecutions.value)
    }
