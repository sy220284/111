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
    wide: Boolean,
    running: Boolean,
    switching: Boolean,
    onMenu: () -> Unit,
    onNavigate: (String) -> Unit,
    onNewThread: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Ink.canvas)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (!wide) {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.size(48.dp)
                        .background(Color(0xFFF8F8F8), CircleShape)
                        .testTag("drawer-toggle")
                ) {
                    Icon(Icons.Outlined.Menu, "打开侧边导航", tint = Ink.parchment)
                }
            }
        }
        Row(
            Modifier.width(196.dp).height(49.dp)
                .background(Color(0xFFF0F0F0), RoundedCornerShape(28.dp))
                .padding(4.dp).testTag("chat-work-switch"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("chat" to "聊天", "home" to "工作").forEach { (route, label) ->
                val chosen = selected == route
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .semantics { this.selected = chosen }
                        .clickable(role = Role.Tab) { onNavigate(route) }
                        .testTag("tab-$route"),
                    shape = RoundedCornerShape(25.dp),
                    color = if (chosen) Ink.canvas else Color.Transparent,
                    border = if (chosen) BorderStroke(.5.dp, Color(0xFFECECEC)) else null,
                    shadowElevation = if (chosen) 1.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.bodyLarge,
                            color = Ink.parchment,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconButton(
                onClick = onNewThread, enabled = !running && !switching,
                modifier = Modifier.size(48.dp)
                    .background(Color(0xFFF8F8F8), CircleShape)
                    .testTag("top-new-chat")
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, "新建聊天",
                    tint = if (running || switching) Ink.muted else Ink.parchment)
            }
        }
    }
}
