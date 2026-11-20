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

/**
 * Narrow phones present two selector controls above two explicit actions.
 * Wider displays use a compact single-row toolbar. Actions remain visible
 * when font scaling increases or the model name grows.
 */
@Composable
fun ConversationControlBar(
    modelName: String,
    mode: String,
    connected: Boolean,
    running: Boolean,
    importingImage: Boolean,
    imageCount: Int,
    onModel: () -> Unit,
    onMode: () -> Unit,
    onDiagnostics: () -> Unit,
    onImage: () -> Unit
) {
    val canAddImage = !running && !importingImage &&
        imageCount < LocalImageAttachments.MAX_FILES
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 390.dp
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ModelControl(modelName, connected, onModel, Modifier.weight(1.15f))
                    ModeControl(mode, !running, onMode, Modifier.weight(.85f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick = onDiagnostics,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        Icon(if (connected) Icons.Outlined.CheckCircle
                            else Icons.Outlined.ErrorOutline,
                            contentDescription = null, tint = if (connected) Ink.jade else Ink.red,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("内核状态", maxLines = 1)
                    }
                    OutlinedButton(onClick = onImage, enabled = canAddImage,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加图片", maxLines = 1)
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModelControl(modelName, connected, onModel, Modifier.weight(1.15f))
                ModeControl(mode, !running, onMode, Modifier.weight(.85f))
                IconButton(onClick = onDiagnostics, modifier = Modifier.size(48.dp)) {
                    Icon(if (connected) Icons.Outlined.CheckCircle
                        else Icons.Outlined.ErrorOutline, contentDescription = "查看内核运行诊断",
                        tint = if (connected) Ink.jade else Ink.red)
                }
                IconButton(onClick = onImage, enabled = canAddImage,
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "添加本地图片",
                        tint = Ink.copper)
                }
            }
        }
    }
}

@Composable
private fun ModelControl(
    modelName: String, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = Ink.muted)
    ) {
        Text(modelName.ifBlank { "默认模型" },
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
    }
}

@Composable
private fun ModeControl(
    mode: String, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(if (mode == "plan") "先计划" else "执行",
            modifier = Modifier.weight(1f, fill = false), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
    }
}
