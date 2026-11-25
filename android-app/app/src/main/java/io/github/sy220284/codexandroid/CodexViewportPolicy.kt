package io.github.sy220284.codexandroid

internal data class CodexMenuBudget(val width: Float, val height: Float, val top: Float)

/** All budgets are in dp. Never demand more space than the actual window has. */
internal object CodexViewportPolicy {
    fun menu(width: Float, height: Float, protectedTop: Float, preferredWidth: Float): CodexMenuBudget {
        require(width > 0 && height > 0 && preferredWidth > 0 && protectedTop >= 0)
        val minimumRow = minOf(48f, height)
        val top = minOf(protectedTop, height - minimumRow)
        val margin = minOf(12f, (height - top - minimumRow).coerceAtLeast(0f))
        val usableWidth = (width - 32f).coerceAtLeast(minOf(width, 48f))
        return CodexMenuBudget(minOf(preferredWidth, usableWidth),
            minOf(420f, height - top - margin), top)
    }

    fun sheetHeight(height: Float, fraction: Float): Float {
        require(height > 0 && fraction > 0 && fraction <= 1)
        return height * fraction
    }
}

