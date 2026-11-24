package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class TaskPanelDraftPolicyTest {
    @Test fun draftsRemainIsolatedAcrossThreadsAndSheetRecreation() {
        var store = emptyMap<String, TaskPanelDraft>()
        store = TaskPanelDraftPolicy.change(store, "thread-a") {
            it.copy(queueText = "尚未提交的队列任务")
        }
        val sheetReopened = store["thread-a"] ?: TaskPanelDraft()
        assertEquals("尚未提交的队列任务", sheetReopened.queueText)
        store = TaskPanelDraftPolicy.change(store, "thread-b") {
            it.copy(queueText = "另一会话的任务", goalText = "目标 B")
        }
        store = TaskPanelDraftPolicy.change(store, "thread-a") {
            it.copy(reviewScope = "custom", reviewText = "检查边界")
        }
        assertEquals("尚未提交的队列任务", store["thread-a"]?.queueText)
        assertEquals("检查边界", store["thread-a"]?.reviewText)
        assertEquals("另一会话的任务", store["thread-b"]?.queueText)
        assertEquals("目标 B", store["thread-b"]?.goalText)
    }

    @Test fun emptyThreadAndAcceptedDraftResetPreserveOtherFields() {
        var store = TaskPanelDraftPolicy.change(emptyMap(), null) {
            it.copy(queueText = "不能归到无会话任务")
        }
        assertTrue(store.isEmpty())
        store = TaskPanelDraftPolicy.change(store, "thread-a") {
            it.copy(queueText = "待提交", goalText = "长期目标")
        }
        store = TaskPanelDraftPolicy.change(store, "thread-a") {
            it.copy(queueText = "")
        }
        assertEquals("", store["thread-a"]?.queueText)
        assertEquals("长期目标", store["thread-a"]?.goalText)
        store = TaskPanelDraftPolicy.change(store, "thread-a") { TaskPanelDraft() }
        assertFalse(store.containsKey("thread-a"))
    }

    @Test fun budgetEditIntentSurvivesSheetRecreationAndIsolatedPerThread() {
        var drafts = TaskPanelDraftPolicy.change(emptyMap(), "thread-a") {
            it.copy(goalText = "修复问题", goalBudget = "9000", goalBudgetEdited = true)
        }
        drafts = TaskPanelDraftPolicy.change(drafts, "thread-b") {
            it.copy(goalText = "另一个目标")
        }
        val reopened = drafts["thread-a"] ?: TaskPanelDraft()
        assertTrue(reopened.goalBudgetEdited)
        assertEquals("9000", reopened.goalBudget)
        assertFalse(drafts["thread-b"]!!.goalBudgetEdited)

        // Explicit undo clears the pending intent without removing the unfinished goal.
        drafts = TaskPanelDraftPolicy.change(drafts, "thread-a") {
            it.copy(goalBudget = "", goalBudgetEdited = false)
        }
        assertEquals("修复问题", drafts["thread-a"]?.goalText)
        assertFalse(drafts["thread-a"]!!.goalBudgetEdited)
    }
}
