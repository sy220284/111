package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 360/393/411dp 共用单行控制，图标按钮均保留 48dp 触控目标。 */
@Composable
fun ConversationControlBar(
    modelName: String, mode: String, connected: Boolean, running: Boolean,
    onModel: () -> Unit, onMode: () -> Unit,
    onDiagnostics: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ModelControl(modelName, onModel, Modifier.weight(1.12f))
        ModeControl(mode, !running, onMode, Modifier.weight(.88f))
        IconButton(onClick = onDiagnostics, modifier = Modifier.size(48.dp)) {
            Icon(if (connected) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = "查看内核运行诊断",
                tint = if (connected) Ink.jade else Ink.red)
        }
    }
}

@Composable
private fun ModelControl(modelName: String,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)) {
        Text(modelName.ifBlank { "默认模型" }, modifier = Modifier.weight(1f, fill = false),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ArrowDropDown, null, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun ModeControl(mode: String, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)) {
        Text(if (mode == "plan") "先计划" else "执行",
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ArrowDropDown, null, modifier = Modifier.size(15.dp))
    }
}
