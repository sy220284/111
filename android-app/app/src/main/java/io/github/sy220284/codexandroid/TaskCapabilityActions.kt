package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Prepare a draft for the selected task; explicit send is still required. */
internal fun appendTaskInstruction(vm: CodexViewModel, expectedThread: String?, instruction: String): Boolean {
    if (vm.activeThread.value != expectedThread || vm.threadSwitching.value ||
        !vm.globalWorkSnapshot().canStartNewTask) return false
    val owner = vm.composerOwner(expectedThread)
    val draft = vm.composerDrafts.value[owner]?.text.orEmpty()
    vm.editComposerText(owner, listOf(draft, instruction).filter { it.isNotBlank() }.joinToString("\n\n"))
    return true
}

@Composable
internal fun TaskCapabilitiesSheet(vm: CodexViewModel, onDismiss: () -> Unit, navigate: (String) -> Unit) {
    CodexDetailSheet(title = "工具与扩展", onDismiss = onDismiss) {
        Text("按任务选择能力。连接、授权与安装以实际内核返回为准。", color = Ink.muted)
        for ((route, label, description) in listOf(
            Triple("skills", "技能", "任务方法与工作规范"),
            Triple("plugins", "插件", "安装与管理扩展"),
            Triple("mcp", "MCP 工具", "外部工具服务"),
            Triple("apps", "连接应用", "已授权的应用资源"),
            Triple("features", "内核能力", "常用功能与高级开关")
        )) {
            CodexSecondaryButton(onClick = { onDismiss(); navigate(route) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) { Text(label); Text(description, color = Ink.muted) }
                CodexIcon(CodexGlyph.ChevronRight, null)
            }
        }
        CodexTextAction(onClick = {
            if (appendTaskInstruction(vm, vm.activeThread.value, "请根据当前项目与任务，检查所需的工具、技能及扩展是否可用。需要新增权限或安装时先向我说明。")) onDismiss()
        }, modifier = Modifier.fillMaxWidth()) { Text("让 Codex 检查所需能力") }
    }
}
