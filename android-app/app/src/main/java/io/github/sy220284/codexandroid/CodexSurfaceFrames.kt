package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalCodexSheetClose = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalCodexSheetAction = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/** 两种明确的窗口形态：短操作居中，长任务使用完整页面。均保留原业务的生命周期。 */
@Composable
fun CodexOverlayFrame(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    containerColor: Color = Ink.elevated,
    contentColor: Color = Ink.parchment,
    content: @Composable ColumnScope.() -> Unit
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    var dismissed by remember { mutableStateOf(false) }
    val closeWithAction: (() -> Unit) -> Unit = { action ->
        if (!dismissed) {
            dismissed = true
            dismiss()
            action()
        }
    }
    val close: () -> Unit = { dismiss() }
    val screen = LocalConfiguration.current
    Dialog(onDismissRequest = close, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = !fullScreen)) {
        val bounds = if (fullScreen) Modifier.fillMaxSize()
            else Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .padding(horizontal = 16.dp).heightIn(max = screen.screenHeightDp.dp * .86f)
        Box((if (fullScreen) Modifier.fillMaxSize() else Modifier.wrapContentSize())
            .imePadding(), contentAlignment = Alignment.Center) {
            Surface(modifier = bounds.then(modifier),
                shape = RoundedCornerShape(if (fullScreen) 0.dp else 24.dp),
                color = containerColor, contentColor = contentColor,
                border = if (fullScreen) null else BorderStroke(1.dp, Ink.border.copy(alpha = .65f)),
                shadowElevation = if (fullScreen) 0.dp else 12.dp) {
                CompositionLocalProvider(LocalCodexSheetClose provides close,
                    LocalCodexSheetAction provides closeWithAction) {
                    Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier),
                        content = content)
                }
            }
        }
    }
}

@Composable
fun codexSheetContentLimit(fraction: Float = .86f): Dp =
    LocalConfiguration.current.screenHeightDp.dp * fraction

/** 普通编辑、说明与危险确认统一主题与形态；操作回调由真实业务持有。 */
@Composable
fun CodexDialogFrame(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    containerColor: Color = Ink.elevated,
    titleContentColor: Color = Ink.parchment,
    textContentColor: Color = Ink.parchment
) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = containerColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor)
}

/** 轻量锚定菜单共享原生 Popup 容器，保留系统返回、焦点与主题。 */
@Composable
internal fun CodexPopupMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val properties = androidx.compose.ui.window.PopupProperties(focusable = focusable)
    if (floating) {
        if (expanded) Popup(
            popupPositionProvider = CodexAnchoredPosition,
            onDismissRequest = onDismissRequest, properties = properties
        ) {
            // DropdownMenu internally requests intrinsic dimensions, which is
            // incompatible with search fields and subcomposed controls.
            Surface(modifier = modifier, shape = RoundedCornerShape(24.dp),
                color = Ink.elevated, contentColor = Ink.parchment, shadowElevation = 8.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    content = content)
            }
        }
    } else {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
            modifier = modifier, properties = properties, content = content)
    }
}

/** Place the menu against its actual anchor and clamp to the visible window. */
internal object CodexAnchoredPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - width
        val alternate = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - width else anchorBounds.left
        val maxX = (windowSize.width - width).coerceAtLeast(0)
        val x = listOf(start, alternate).firstOrNull { it in 0..maxX } ?: start.coerceIn(0, maxX)
        val above = anchorBounds.top - height
        val below = anchorBounds.bottom
        val maxY = (windowSize.height - height).coerceAtLeast(0)
        val y = when {
            below in 0..maxY -> below
            above in 0..maxY -> above
            anchorBounds.top > windowSize.height - anchorBounds.bottom -> above.coerceIn(0, maxY)
            else -> below.coerceIn(0, maxY)
        }
        return IntOffset(x, y)
    }
}
