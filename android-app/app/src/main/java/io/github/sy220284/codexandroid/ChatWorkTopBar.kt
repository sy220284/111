package io.github.sy220284.codexandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 单一任务首页：空会话保持顶部留白，只展示实际会话标题。 */
@Composable
internal fun ChatWorkTopBar(
    conversationTitle: String?,
    newThreadBlockReason: String?,
    otherPendingCount: Int,
    onApprovals: () -> Unit,
    onMenu: () -> Unit,
    onNewThread: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Ink.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp)
            .testTag("conversation-top-bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CodexIconAction(
            onClick = onMenu,
            modifier = Modifier.size(48.dp)
                .background(Ink.recessed, CircleShape)
                .testTag("drawer-toggle")
        ) {
            CodexIcon(CodexGlyph.Sidebar, "打开侧边导航", tint = Ink.parchment)
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            if (!conversationTitle.isNullOrBlank()) {
                Row(Modifier.testTag("conversation-top-title"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CodexIcon(CodexGlyph.BrandCodex, null, modifier = Modifier.size(18.dp),
                        tint = Ink.parchment)
                    Text(conversationTitle, style = MaterialTheme.typography.titleMedium,
                        color = Ink.parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (otherPendingCount > 0) {
            CodexIconAction(onClick = onApprovals,
                modifier = Modifier.size(48.dp).testTag("chat-other-approvals")) {
                CodexIcon(CodexGlyph.TaskApproval, "其他会话有 $otherPendingCount 项待处理", tint = Ink.warning)
            }
        }
        CodexIconAction(
            onClick = onNewThread, enabled = newThreadBlockReason == null,
            modifier = Modifier.size(48.dp)
                .background(Ink.recessed, CircleShape)
                .testTag("top-new-chat")
                .explainDisabled(newThreadBlockReason != null, newThreadBlockReason.orEmpty())
        ) {
            CodexIcon(CodexGlyph.EditNote, "新建任务",
                tint = if (newThreadBlockReason != null) Ink.muted else Ink.parchment)
        }
    }
}
