package io.github.sy220284.codexandroid

import androidx.compose.runtime.*

/** Task controls live beside the composer; session actions are transient. */
@Composable
fun ThreadContextTools(
    vm: CodexViewModel,
    navigate: (String) -> Unit,
    onDismiss: () -> Unit,
    onCompact: () -> Unit
) {
    val thread by vm.activeThread.collectAsState()
    val running by vm.running.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    val starting by vm.firstThreadStarting.collectAsState()
    val loading by vm.loading.collectAsState()
    val idle = thread != null && !running && !switching && !starting &&
        "threadFork:$thread" !in loading && "threadCompact:$thread" !in loading
    CodexFloatingActionMenu(title = "会话操作", onDismiss = onDismiss, actions = listOf(
        CodexSheetAction("整理上下文", CodexGlyph.Compress, onCompact, enabled = idle),
        CodexSheetAction("历史回退", CodexGlyph.History, { navigate("history-tools") }, enabled = idle),
        CodexSheetAction("关联资料", CodexGlyph.Attachment, { navigate("thread-attachments") },
            enabled = thread != null && !switching)
    ))
}
