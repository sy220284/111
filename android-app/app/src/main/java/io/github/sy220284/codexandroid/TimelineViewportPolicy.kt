package io.github.sy220284.codexandroid

/** 可见列表包含可选的旧记录入口；同一条流式消息更新也是尾部变化。 */
internal object TimelineViewportPolicy {
    data class Change(val changed: Boolean, val newSteps: Int)

    fun latestIndex(size: Int, olderPageVisible: Boolean): Int =
        if (size <= 0) -1 else size - 1 + if (olderPageVisible) 1 else 0

    fun change(previousSize: Int, previousTail: TimelineItem?,
        size: Int, tail: TimelineItem?): Change {
        val newSteps = if (tail != null && tail.id != previousTail?.id)
            (size - previousSize).coerceAtLeast(0) else 0
        return Change(tail != previousTail, newSteps)
    }
}
