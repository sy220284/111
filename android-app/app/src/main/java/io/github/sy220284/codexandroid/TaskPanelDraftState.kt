package io.github.sy220284.codexandroid

/**
 * Draft values for task-owned sheets. One entry per thread keeps unfinished
 * forms independent even while pages, dialogs and bottom sheets are recreated.
 */
internal data class TaskPanelDraft(
    val queueText: String = "",
    val goalText: String = "",
    val goalStatus: String = "active",
    val goalBudget: String = "",
    /** Only an explicitly confirmed budget edit is included in the next goal write. */
    val goalBudgetEdited: Boolean = false,
    val reviewScope: String = "uncommittedChanges",
    val reviewText: String = ""
)

internal object TaskPanelDraftPolicy {
    fun change(
        states: Map<String, TaskPanelDraft>,
        threadId: String?,
        edit: (TaskPanelDraft) -> TaskPanelDraft
    ): Map<String, TaskPanelDraft> {
        val id = threadId?.takeIf(String::isNotBlank) ?: return states
        val next = edit(states[id] ?: TaskPanelDraft())
        return when {
            next == TaskPanelDraft() -> states - id
            next == states[id] -> states
            else -> states + (id to next)
        }
    }
}
