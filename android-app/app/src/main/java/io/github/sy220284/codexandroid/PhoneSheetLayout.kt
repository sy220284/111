package io.github.sy220284.codexandroid

/** 手机竖屏弹层仅按内容量决定高度。 */
internal object PhoneSheetLayout {
    fun goalFraction(hasGoal: Boolean): Float = if (hasGoal) 0.82f else 0.68f

    fun queueFraction(itemCount: Int): Float = when {
        itemCount <= 2 -> 0.62f
        itemCount <= 6 -> 0.78f
        else -> 0.90f
    }
}
