package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalCodexSheetClose = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalCodexSheetAction = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/** 所有详情、快捷选择和专业任务面板共享一个原生容器。保留各业务页面的滚动、状态与返回策略。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexSheetFrame(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor: Color = Ink.elevated,
    contentColor: Color = Ink.parchment,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle(color = Ink.muted) },
    content: @Composable ColumnScope.() -> Unit
) {
    val reduceMotion by UiPreferences.reducedMotion.collectAsState()
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember(sheetState) { mutableStateOf(false) }
    var dismissed by remember(sheetState) { mutableStateOf(false) }
    var pendingAction by remember(sheetState) { mutableStateOf<(() -> Unit)?>(null) }
    val finish: () -> Unit = {
        if (!dismissed) {
            dismissed = true
            val action = pendingAction
            pendingAction = null
            dismiss()
            action?.invoke()
        }
    }
    val closeWithAction: (() -> Unit) -> Unit = { action ->
        if (!closing && !dismissed) {
            pendingAction = action
            closing = true
            if (reduceMotion) finish()
            else scope.launch {
                try {
                    sheetState.hide()
                    if (!sheetState.isVisible) finish()
                } finally {
                    closing = false
                    if (!dismissed) pendingAction = null
                }
            }
        }
    }
    val close: () -> Unit = { closeWithAction {} }
    ModalBottomSheet(onDismissRequest = finish, modifier = modifier, sheetState = sheetState,
        containerColor = containerColor, contentColor = contentColor, dragHandle = dragHandle,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        val column = this
        CompositionLocalProvider(LocalCodexSheetClose provides close,
            LocalCodexSheetAction provides closeWithAction) { column.content() }
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
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
            modifier = modifier, properties = properties, shape = RoundedCornerShape(24.dp),
            containerColor = Ink.elevated, tonalElevation = 0.dp, shadowElevation = 8.dp,
            content = content)
    } else {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest,
            modifier = modifier, properties = properties, content = content)
    }
}
