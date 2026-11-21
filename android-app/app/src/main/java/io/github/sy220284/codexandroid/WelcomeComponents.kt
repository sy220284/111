package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 参考简洁双列任务卡片：白底、轻边线、圆形图标和直接动作。 */
@Composable
fun WelcomeActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tone: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null,
                role = Role.Button, onClick = onClick),
        color = Ink.canvas,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (pressed) tone else Ink.border),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp).heightIn(min = 99.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(modifier = Modifier.size(36.dp), shape = CircleShape,
                color = Ink.canvas,
                border = BorderStroke(1.dp, tone.copy(alpha = .33f))) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tone,
                        modifier = Modifier.size(21.dp))
                }
            }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Ink.muted, maxLines = 2,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}
