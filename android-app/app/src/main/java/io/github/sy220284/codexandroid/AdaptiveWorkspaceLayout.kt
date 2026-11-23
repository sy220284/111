package io.github.sy220284.codexandroid

/**
 * 统一主工作区与任务弹层的屏幕尺寸规则。以实际可用高度判断横屏手机，
 * 避免只看宽度导致输入栏、文件及任务结果被压缩。
 */
internal object AdaptiveWorkspaceLayout {
    const val expandedWidthDp = 840f
    const val compactHeightDp = 480f

    fun persistentSidebar(widthDp: Float, heightDp: Float): Boolean =
        widthDp >= expandedWidthDp && heightDp >= compactHeightDp

    /** 少量条目时只占适度空间；列表扩展后才让弹层使用大部分屏幕。 */
    fun queueSheetFraction(itemCount: Int, compactHeight: Boolean): Float = when {
        compactHeight -> 0.94f
        itemCount <= 2 -> 0.62f
        itemCount <= 6 -> 0.78f
        else -> 0.90f
    }
}
