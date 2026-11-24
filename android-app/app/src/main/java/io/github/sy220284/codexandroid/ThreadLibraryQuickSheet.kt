package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Task-scoped thread selection retains a closable sheet; global history lives in a full page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadLibraryQuickSheet(
    vm: CodexViewModel,
    onDismiss: () -> Unit,
    navigate: (String) -> Unit,
    initialFullText: Boolean = false,
    continuationLabel: String? = null,
    onThreadSelected: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.canvas,
        contentColor = Ink.parchment
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f)
            .testTag("thread-library-sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("选择会话", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    CodexIcon(CodexGlyph.Close, contentDescription = "关闭会话选择")
                }
            }
            if (continuationLabel != null) {
                Text("选择会话后继续：$continuationLabel",
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            }
            Box(Modifier.weight(1f)) {
                ThreadLibraryPage(vm, navigate, initialFullText, onThreadSelected)
            }
        }
    }
}
