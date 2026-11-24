package io.github.sy220284.codexandroid

/** 手机竖屏弹层仅按内容量决定高度。 */
internal object PhoneSheetLayout {
    fun goalFraction(hasGoal: Boolean): Float = if (hasGoal) 0.94f else 0.86f

    fun queueFraction(itemCount: Int): Float = when {
        itemCount <= 2 -> 0.72f
        itemCount <= 6 -> 0.86f
        else -> 0.94f
    }
}
