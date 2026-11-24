package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Output events do not change input, confirmation or command controls. */
internal data class TerminalControlState(
    val processId: String, val command: String, val tty: Boolean, val running: Boolean,
    val terminating: Boolean, val outputTruncated: Boolean, val exitCode: Int?, val error: String?
) {
    companion object {
        fun from(state: TerminalSessionState) = TerminalControlState(state.processId, state.command,
            state.tty, state.running, state.terminating, state.outputTruncated, state.exitCode, state.error)
    }
}

internal data class TerminalRenderedOutput(val processId: String, val stdout: String, val stderr: String) {
    companion object {
        fun from(state: TerminalSessionState) =
            TerminalRenderedOutput(state.processId, state.stdoutText, state.stderrText)
    }
}

@Composable
internal fun TerminalOutputHeader(vm: CodexViewModel, processId: String,
    output: State<TerminalRenderedOutput>, onRead: (String) -> Unit) {
    val stdout = output.value.takeIf { it.processId == processId }?.stdout.orEmpty()
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("标准输出", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        CodexTextAction(onClick = {
            val current = vm.terminalSession.value
            if (current.processId == processId) {
                clipboard.setText(AnnotatedString(current.stdoutText))
                vm.actionFeedback.tryEmit(ActionFeedbackReceipt("终端输出已复制"))
            }
        }, enabled = stdout.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
            CodexIcon(CodexGlyph.ContentCopy, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text("复制")
        }
        CodexTextAction(onClick = {
            val current = vm.terminalSession.value
            if (current.processId == processId) onRead(current.stdoutText)
        }, enabled = stdout.length > 16_000,
            modifier = Modifier.heightIn(min = 48.dp).testTag("terminal-full-stdout")) {
            Text("查看完整输出")
        }
    }
}

@Composable
internal fun TerminalOutputPreview(processId: String, running: Boolean, output: State<TerminalRenderedOutput>) {
    val snapshot = output.value.takeIf { it.processId == processId }
    val stdout = snapshot?.stdout.orEmpty()
    val stderr = snapshot?.stderr.orEmpty()
    SelectionContainer {
        Text(stdout.takeLast(16_000).ifBlank {
            if (running) "等待命令输出…" else "(无标准输出)"
        }, fontFamily = FontFamily.Monospace, color = Ink.parchment,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("terminal-stdout"))
    }
    if (stderr.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("错误输出", style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(stderr.takeLast(16_000), fontFamily = FontFamily.Monospace, color = Ink.red,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
