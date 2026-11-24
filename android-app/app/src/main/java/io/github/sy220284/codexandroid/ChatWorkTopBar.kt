package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 原生工作路由与参考图形态一一对应。 */
@Composable
internal fun ChatWorkTopBar(
    selected: String,
    conversationTitle: String = "任务对话",
    newThreadBlockReason: String?,
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit,
    onNewThread: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Ink.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            CodexIconAction(
                onClick = onMenu,
                modifier = Modifier.size(48.dp)
                    .background(Ink.recessed, CircleShape)
                    .testTag("drawer-toggle")
            ) {
                CodexIcon(CodexGlyph.Sidebar, "打开侧边导航", tint = Ink.parchment)
            }
        }
        Column(
            Modifier.weight(2f).testTag("conversation-top-title"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodexIcon(CodexGlyph.BrandCodex, null, modifier = Modifier.size(18.dp), tint = Ink.parchment)
            Text(
                conversationTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Ink.parchment,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            CodexIconAction(
                onClick = onNewThread, enabled = newThreadBlockReason == null,
                modifier = Modifier.size(48.dp)
                    .background(Ink.recessed, CircleShape)
                    .testTag("top-new-chat")
                    .explainDisabled(newThreadBlockReason != null, newThreadBlockReason.orEmpty())
            ) {
                CodexIcon(CodexGlyph.EditNote, "新建聊天",
                    tint = if (newThreadBlockReason != null) Ink.muted else Ink.parchment)
            }
        }
    }
}
