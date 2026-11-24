package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Only successful archive/restore responses can authorize the inverse operation. */
data class ThreadUndoOffer(val ticket: Long, val kind: String, val ids: List<String>)

internal object ThreadOrganizationPolicy {
    fun isOrganization(method: String): Boolean =
        method in setOf("thread/archive", "thread/unarchive", "thread/delete")
    fun inverse(method: String): String? = when (method) {
        "thread/archive" -> "unarchive"
        "thread/unarchive" -> "archive"
        else -> null
    }
    fun notification(method: String): String? = when (method) {
        "thread/archived" -> "thread/archive"
        "thread/unarchived" -> "thread/unarchive"
        "thread/deleted" -> "thread/delete"
        else -> null
    }
}

/** One latest explicit operation; an opposite server event or a new write invalidates an old undo. */
internal class ThreadUndoGate {
    private var sequence = 0L
    private var method: String? = null
    private var targets: Set<String> = emptySet()
    private var offer: ThreadUndoOffer? = null

    fun invalidate() {
        sequence++
        method = null
        targets = emptySet()
        offer = null
    }
    fun begin(method: String, ids: List<String>): Long? {
        if (!ThreadOrganizationPolicy.isOrganization(method)) return null
        invalidate()
        if (ThreadOrganizationPolicy.inverse(method) == null || ids.isEmpty()) return null
        this.method = method
        targets = ids.toSet()
        return sequence
    }
    fun complete(ticket: Long?, successfulIds: List<String>): ThreadUndoOffer? {
        if (ticket == null || ticket != sequence || offer != null) return null
        val kind = method?.let(ThreadOrganizationPolicy::inverse) ?: return null
        val actual = successfulIds.distinct().filter { it in targets }
        if (actual.isEmpty()) return null
        targets = actual.toSet()
        return ThreadUndoOffer(ticket, kind, actual).also { offer = it }
    }
    fun observe(notification: String, id: String) {
        val changed = ThreadOrganizationPolicy.notification(notification) ?: return
        if (id in targets && changed != method) invalidate()
    }
    fun isCurrent(candidate: ThreadUndoOffer): Boolean = offer == candidate
    fun consume(candidate: ThreadUndoOffer): Boolean {
        if (!isCurrent(candidate)) return false
        invalidate()
        return true
    }
}

/** Single and batch operations share the same receipt ownership; only confirmed IDs can be undone. */
internal class ThreadOrganizationController(private val vm: CodexViewModel) {
    private val gate = ThreadUndoGate()
    val revision = MutableStateFlow(0L)
    fun invalidate() = gate.invalidate()
    fun begin(method: String, ids: List<String>) = gate.begin(method, ids)
    fun complete(ticket: Long?, ids: List<String>) = gate.complete(ticket, ids)
    fun isCurrent(offer: ThreadUndoOffer) = gate.isCurrent(offer)
    fun observe(method: String, id: String) {
        gate.observe(method, id)
        revision.value++
    }
    fun undo(offer: ThreadUndoOffer) {
        if (!gate.consume(offer)) {
            vm.viewModelScope.launch { vm.actionFeedback.emit(ActionFeedbackReceipt(
                "会话状态已更新，请在会话历史中重新核对", isError = true)) }
            return
        }
        execute(offer.kind, offer.ids, offerUndo = false) { revision.value++ }
    }

    fun execute(kind: String, ids: List<String>, offerUndo: Boolean = true, after: () -> Unit) = with(vm) {
        fun refuse(reason: String) {
            errors.value = errors.value + ("threadLibraryBulk" to reason)
            viewModelScope.launch { actionFeedback.emit(ActionFeedbackReceipt(reason, isError = true)) }
        }
        val method = try {
            ThreadBulkActionPolicy.method(kind)
        } catch (failure: IllegalArgumentException) {
            refuse(failure.message ?: "未知会话整理操作")
            return@with
        }
        val targets = ThreadBulkActionPolicy.uniqueIds(if (offerUndo) ids.flatMap {
            answerVersions.state.value.members(it).let { group -> if (kind == "delete") group.asReversed() else group }
        } else ids)
        if (targets.isEmpty()) return@with
        if (!globalWorkSnapshot().canStartNewTask) {
            refuse("本机备份或恢复正在进行，完成后再整理会话")
            return@with
        }
        if (firstThreadStarting.value || threadSwitching.value ||
            targets.any { it in threadExecutions.value } ||
            "threadLibraryBulk" in loading.value || "threadLibraryAction" in loading.value) {
            refuse("当前任务仍在执行或已有整理操作，请完成后重试")
            return@with
        }
        val ticket = gate.begin(method, targets)
        bulkCompletedThreadIds.value = emptySet()
        bulkActionSummary.value = "正在处理 0/${targets.size} 项会话"
        errors.value = errors.value - "threadLibraryBulk"
        loading.value = loading.value + "threadLibraryBulk"
        viewModelScope.launch {
            val completed = mutableListOf<String>()
            try {
                for (id in targets) {
                    check(!firstThreadStarting.value && !threadSwitching.value &&
                        id !in threadExecutions.value) {
                        "批量处理期间启动了新任务，已停止后续操作"
                    }
                    bridge.request(method, JSONObject().put("threadId", id))
                    completed += id
                    bulkCompletedThreadIds.value = completed.toSet()
                    bulkActionSummary.value = "正在处理 ${completed.size}/${targets.size} 项会话"
                }
                bulkActionSummary.value = "已${ThreadBulkActionPolicy.display(kind)} ${completed.size} 条会话"
                actionFeedback.emit(ActionFeedbackReceipt(bulkActionSummary.value,
                    undo = if (offerUndo) gate.complete(ticket, completed) else null))
            } catch (failure: Exception) {
                val reason = "已完成 ${completed.size}/${targets.size} 条；" +
                    (failure.message ?: "操作中断，请核对未完成项")
                bulkActionSummary.value = reason
                errors.value = errors.value + ("threadLibraryBulk" to reason)
                actionFeedback.emit(ActionFeedbackReceipt(reason, isError = true,
                    undo = if (offerUndo) gate.complete(ticket, completed) else null))
            } finally {
                if (activeThread.value in bulkCompletedThreadIds.value && !running.value) newThread()
                loading.value = loading.value - "threadLibraryBulk"
                after()
                syncRecentThreads()
            }
        }
        Unit
    }
}

/** Snackbar presents only a still-valid receipt; the controller validates it again on click. */
internal suspend fun presentActionFeedback(host: SnackbarHostState, receipt: ActionFeedbackReceipt,
    isCurrent: (ThreadUndoOffer) -> Boolean, onUndo: (ThreadUndoOffer) -> Unit) {
    val undo = receipt.undo?.takeIf(isCurrent)
    val result = host.showSnackbar(
        message = receipt.message,
        actionLabel = if (undo != null) "撤销" else null,
        withDismissAction = receipt.isError || undo != null,
        duration = if (receipt.isError || undo != null) SnackbarDuration.Long else SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed && undo != null) onUndo(undo)
}
