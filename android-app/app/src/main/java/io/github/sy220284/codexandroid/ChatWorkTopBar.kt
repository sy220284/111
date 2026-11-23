package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.ui.unit.dp

/** 原生工作路由与参考图形态一一对应。 */
@Composable
internal fun ChatWorkTopBar(
    selected: String,
    newThreadBlockReason: String?,
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit,
    onNewThread: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Ink.canvas)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconButton(
                onClick = onMenu,
                modifier = Modifier.size(48.dp)
                    .background(Ink.recessed, CircleShape)
                    .testTag("drawer-toggle")
            ) {
                Icon(Icons.Outlined.Menu, "打开侧边导航", tint = Ink.parchment)
            }
        }
        Column(
            Modifier.weight(2f).testTag("conversation-top-title"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (selected == "chat") "任务对话" else "工作台",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.parchment,
                maxLines = 1
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconButton(
                onClick = onNewThread, enabled = newThreadBlockReason == null,
                modifier = Modifier.size(48.dp)
                    .background(Ink.recessed, CircleShape)
                    .testTag("top-new-chat")
                    .explainDisabled(newThreadBlockReason != null, newThreadBlockReason.orEmpty())
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, "新建聊天",
                    tint = if (newThreadBlockReason != null) Ink.muted else Ink.parchment)
            }
        }
    }
}
