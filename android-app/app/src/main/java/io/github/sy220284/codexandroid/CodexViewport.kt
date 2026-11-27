package io.github.sy220284.codexandroid

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Scaffold provides its measured header inset, including any dynamic system bars. */
internal val LocalCodexProtectedTop = compositionLocalOf { 0.dp }

internal data class CodexViewport(val width: Dp, val height: Dp)
private fun visibleFrame(view: View): Rect = Rect().also(view::getWindowVisibleDisplayFrame)

private data class CodexWindowViewport(val view: View, val viewport: CodexViewport)
private val LocalCodexWindowViewport = compositionLocalOf<CodexWindowViewport?> { null }

/** A window shares one observer. Dialogs and other distinct native windows measure themselves. */
@Composable
internal fun ProvideCodexViewport(content: @Composable () -> Unit) {
    val view = LocalView.current
    val viewport = observeCodexViewport(view)
    CompositionLocalProvider(LocalCodexWindowViewport provides CodexWindowViewport(view, viewport), content = content)
}

@Composable
internal fun rememberCodexViewport(): CodexViewport {
    val view = LocalView.current
    val shared = LocalCodexWindowViewport.current
    return if (shared != null && shared.view === view) shared.viewport else observeCodexViewport(view)
}

/** Observe WindowManager's actual visible rectangle after resize/IME/system-bar changes.
 * Configuration is used only before the view has a measured frame.
 */
@Composable
private fun observeCodexViewport(view: View): CodexViewport {
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
            else if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(density) {
        CodexViewport(
            if (frame.width() > 0) frame.width().toDp() else configuration.screenWidthDp.coerceAtLeast(1).dp,
            if (frame.height() > 0) frame.height().toDp() else configuration.screenHeightDp.coerceAtLeast(1).dp
        )
    }
}

/** Layout uses the same combined system and app typography scale as the theme. */
@Composable
internal fun codexEffectiveFontScale(): Float {
    val appScale by UiPreferences.fontScale.collectAsStateWithLifecycle()
    return LocalDensity.current.fontScale * appScale
}


/** Keep the same reading and input column as the existing full-screen reader. */
@Composable
internal fun CodexPageWidth(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = CodexContentMaxWidth).fillMaxSize(), content = content)
    }
}

internal val CodexContentMaxWidth = 840.dp
