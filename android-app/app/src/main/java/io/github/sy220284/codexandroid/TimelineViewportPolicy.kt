package io.github.sy220284.codexandroid

/** 可见列表包含可选的旧记录入口；同一条流式消息更新也是尾部变化。 */
internal object TimelineViewportPolicy {
    data class Change(val changed: Boolean, val newSteps: Int)

    /** Display rows remain chronological; a reversed lazy list puts the
     * newest row at index zero. The optional recovery row is newer than
     * the projected rows; the older-record control is always at the end. */
    fun physicalIndex(logicalIndex: Int, displayCount: Int, recoveryCount: Int): Int =
        if (logicalIndex in 0 until displayCount) displayCount - 1 - logicalIndex + recoveryCount else -1

    fun latestIndex(displayCount: Int): Int = if (displayCount > 0) 0 else -1

    fun measuredCount(displayCount: Int, olderPageVisible: Boolean): Int =
        displayCount + if (olderPageVisible && displayCount > 0) 1 else 0

    enum class FollowAction { Await, RequestTarget, AtEnd }

    /** Native reverseLayout anchors a growing row at its bottom. Only
     * changed list identity/geometry needs a new index-zero request. */
    fun followAction(expectedCount: Int, measuredCount: Int, viewportHeight: Int,
        expectedKey: String?, measuredKey: Any?, canScrollTowardLatest: Boolean): FollowAction = when {
        expectedCount <= 0 || measuredCount != expectedCount || viewportHeight <= 0 || expectedKey == null ->
            FollowAction.Await
        measuredKey != expectedKey || canScrollTowardLatest -> FollowAction.RequestTarget
        else -> FollowAction.AtEnd
    }

    fun change(previousSize: Int, previousTail: TimelineItem?,
        size: Int, tail: TimelineItem?): Change {
        val newSteps = if (tail != null && tail.id != previousTail?.id)
            (size - previousSize).coerceAtLeast(0) else 0
        return Change(tail != previousTail, newSteps)
    }
}
