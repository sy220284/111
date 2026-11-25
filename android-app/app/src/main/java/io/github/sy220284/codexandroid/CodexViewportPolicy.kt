package io.github.sy220284.codexandroid

internal data class CodexPageSpacing(val horizontal: Float, val vertical: Float, val gap: Float)

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

    /** Estimate space required by controls using combined system and app text scale. */
    fun fitsActions(width: Float, fontScale: Float, count: Int, nominalItemWidth: Float,
                    gap: Float = 8f): Boolean {
        require(width >= 0 && fontScale > 0 && count > 0 && nominalItemWidth > 0 && gap >= 0)
        val itemWidth = maxOf(48f, nominalItemWidth * maxOf(1f, fontScale))
        return width >= count * itemWidth + (count - 1) * gap
    }

    /** Reserve the full touch targets first, then protect model/provider/effort readability. */
    fun composerModelOwnRow(width: Float, fontScale: Float, iconActions: Int): Boolean {
        require(width >= 0 && fontScale > 0 && iconActions in 2..4)
        return width - 48f * iconActions < 132f * maxOf(1f, fontScale)
    }

    fun pageSpacing(width: Float, height: Float, compact: Boolean): CodexPageSpacing {
        require(width > 0 && height > 0)
        val horizontal = minOf(if (compact || width < 350f) 12f else 18f, width / 8f)
        val short = height < 480f
        return CodexPageSpacing(horizontal, if (compact || short) 12f else 20f,
            if (compact || short) 11f else 16f)
    }

    fun compactWorkArea(height: Float, imeVisible: Boolean, fontScale: Float): Boolean {
        require(height > 0 && fontScale > 0)
        return imeVisible || height < 480f || height < 560f && fontScale >= 1.5f
    }

    fun sheetHeight(height: Float, fraction: Float): Float {
        require(height > 0 && fraction > 0 && fraction <= 1)
        return height * fraction
    }
}

