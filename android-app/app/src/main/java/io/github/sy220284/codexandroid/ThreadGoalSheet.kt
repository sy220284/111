package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 当前会话目标的面板入口；所有读取/修改/清除仍使用 ThreadGoalPage 中的 RPC。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadGoalSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    CodexOverlayFrame(onDismissRequest = onDismiss, fullScreen = true,
        containerColor = Ink.canvas, contentColor = Ink.parchment) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("任务目标", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                CodexIconAction(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.Close, contentDescription = "关闭任务目标")
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ThreadGoalPage(vm, inSheet = true)
            }
        }
    }
}
