package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
        containerColor = containerColor, contentColor = contentColor, dragHandle = dragHandle,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), content = content)
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
