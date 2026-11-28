package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    private var undoOwner: TaskRequestOwner? = null
    fun invalidate() {
        undoOwner = null
        gate.invalidate()
    }
    fun begin(method: String, ids: List<String>): Long? {
        if (!ThreadOrganizationPolicy.isOrganization(method)) return null
        undoOwner = vm.captureTaskOwner()
        return gate.begin(method, ids)
    }
    fun complete(ticket: Long?, ids: List<String>) =
        if (undoOwner?.current(vm) == true) gate.complete(ticket, ids) else null
    fun isCurrent(offer: ThreadUndoOffer) = undoOwner?.current(vm) == true && gate.isCurrent(offer)
    fun observe(method: String, id: String) {
        gate.observe(method, id)
        revision.value++
    }
    fun undo(offer: ThreadUndoOffer) {
        if (!isCurrent(offer) || !gate.consume(offer)) {
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
        val owner = captureTaskOwner() ?: run {
            refuse("运行核心尚未连接，请重新连接后操作")
            return@with
        }
        val ticket = begin(method, targets)
        bulkCompletedThreadIds.value = emptySet()
        bulkActionSummary.value = "正在处理 0/${targets.size} 项会话"
        errors.value = errors.value - "threadLibraryBulk"
        loading.value = loading.value + "threadLibraryBulk"
        viewModelScope.launch {
            val completed = mutableListOf<String>()
            var cancelled = false
            try {
                for (id in targets) {
                    check(!firstThreadStarting.value && !threadSwitching.value &&
                        id !in threadExecutions.value) {
                        "批量处理期间启动了新任务，已停止后续操作"
                    }
                    owner.request(vm, method, JSONObject().put("threadId", id))
                    completed += id
                    bulkCompletedThreadIds.value = completed.toSet()
                    bulkActionSummary.value = "正在处理 ${completed.size}/${targets.size} 项会话"
                }
                bulkActionSummary.value = "已${ThreadBulkActionPolicy.display(kind)} ${completed.size} 条会话"
                actionFeedback.emit(ActionFeedbackReceipt(bulkActionSummary.value,
                    undo = if (offerUndo) complete(ticket, completed) else null))
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) {
                    cancelled = true
                    throw failure
                }
                if (!owner.current(vm)) return@launch
                val reason = "已完成 ${completed.size}/${targets.size} 条；" +
                    (failure.message ?: "操作中断，请核对未完成项")
                bulkActionSummary.value = reason
                errors.value = errors.value + ("threadLibraryBulk" to reason)
                actionFeedback.emit(ActionFeedbackReceipt(reason, isError = true,
                    undo = if (offerUndo) complete(ticket, completed) else null))
            } finally {
                loading.value = loading.value - "threadLibraryBulk"
                if (!cancelled && owner.current(vm)) {
                    if (activeThread.value in completed && !running.value) newThread()
                    after()
                    syncRecentThreads()
                } else if (!cancelled) {
                    bulkCompletedThreadIds.value = emptySet()
                    bulkActionSummary.value = ""
                    // Reconcile authoritative state; never replay the old mutation.
                    syncRecentThreads()
                }
            }
        }
        Unit
    }
}
