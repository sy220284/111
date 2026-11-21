package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 当前会话目标的面板入口；所有读取/修改/清除仍使用 ThreadGoalPage 中的 RPC。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadGoalSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.canvas, contentColor = Ink.parchment) {
        Box(Modifier.fillMaxWidth().heightIn(max = 690.dp).navigationBarsPadding()) {
            ThreadGoalPage(vm)
        }
    }
}
