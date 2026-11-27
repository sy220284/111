package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeSafetyRecourseCard(vm: CodexViewModel, thread: String?, item: TimelineItem, failed: TimelineItem?) {
    val raw = item.metadata() ?: return
    if (failed?.metadata()?.optString("safetyDomain") != raw.optString("domain")) return
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("safety-recourse"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (raw.optString("status") == "loading") "正在准备安全替代建议" else raw.optString("explanation"), color = Ink.muted)
        val options = raw.optJSONArray("suggestions")
        if (options != null) for (index in 0 until options.length()) {
            val option = options.getJSONObject(index)
            CodexSecondaryButton(onClick = {
                if (thread == null || vm.activeThread.value != thread || vm.running.value || vm.threadSwitching.value) {
                    error = "会话状态已变化，请稍后选择"
                } else if (vm.composerDrafts.value[vm.composerOwner(thread)]?.hasInput == true) {
                    error = "请先保存当前草稿，安全建议不会覆盖已有内容"
                } else {
                    vm.editComposerText(thread, option.getString("prompt"))
                    error = null
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(option.getString("label") + " · 填入草稿") }
        }
        error?.let { ErrorText(it) }
    }
}
