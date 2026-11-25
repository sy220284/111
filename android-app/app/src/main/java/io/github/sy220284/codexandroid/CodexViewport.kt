package io.github.sy220284.codexandroid

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scaffold provides its measured header inset, including any dynamic system bars. */
internal val LocalCodexProtectedTop = compositionLocalOf { 0.dp }

internal data class CodexViewport(val width: Dp, val height: Dp)
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

private fun visibleFrame(view: View): Rect = Rect().also(view::getWindowVisibleDisplayFrame)

/** Observe WindowManager's actual visible rectangle after resize/IME/system-bar changes.
 * Configuration is used only before the view has a measured frame.
 */
@Composable
internal fun rememberCodexViewport(): CodexViewport {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var frame by remember(view) { mutableStateOf(visibleFrame(view)) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val next = visibleFrame(view)
            if (next != frame) frame = next
        }
        observer.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            else view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(density) {
        CodexViewport(
            if (frame.width() > 0) frame.width().toDp() else configuration.screenWidthDp.coerceAtLeast(1).dp,
            if (frame.height() > 0) frame.height().toDp() else configuration.screenHeightDp.coerceAtLeast(1).dp
        )
    }
}
