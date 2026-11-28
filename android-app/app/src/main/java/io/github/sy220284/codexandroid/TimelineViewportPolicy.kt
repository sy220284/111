package io.github.sy220284.codexandroid

/** 可见列表包含可选的旧记录入口；同一条流式消息更新也是尾部变化。 */
internal object TimelineViewportPolicy {
    data class Change(val changed: Boolean, val newSteps: Int)

    fun latestIndex(size: Int, olderPageVisible: Boolean): Int =
        if (size <= 0) -1 else size - 1 + if (olderPageVisible) 1 else 0

    /** A row leaving or entering the viewport is a scroll event, not content growth. */
    fun tailResized(previous: Int, current: Int): Boolean =
        previous > 0 && current > 0 && previous != current

    fun measuredTargetReady(index: Int, measuredCount: Int, viewportHeight: Int): Boolean =
        index >= 0 && measuredCount == index + 1 && viewportHeight > 0

    enum class FollowAction { Await, RequestTarget, AlignTail, AtEnd }

    /** A request is complete only when the measured last row is visible and
     * the list cannot scroll forward. A frame callback alone is not a receipt. */
    fun followAction(index: Int, measuredCount: Int, viewportHeight: Int,
        visibleTailSize: Int, canScrollForward: Boolean): FollowAction = when {
        !measuredTargetReady(index, measuredCount, viewportHeight) -> FollowAction.Await
        visibleTailSize < 0 -> FollowAction.RequestTarget
        canScrollForward -> FollowAction.AlignTail
        else -> FollowAction.AtEnd
    }

    fun change(previousSize: Int, previousTail: TimelineItem?,
        size: Int, tail: TimelineItem?): Change {
        val newSteps = if (tail != null && tail.id != previousTail?.id)
            (size - previousSize).coerceAtLeast(0) else 0
        return Change(tail != previousTail, newSteps)
    }
}
