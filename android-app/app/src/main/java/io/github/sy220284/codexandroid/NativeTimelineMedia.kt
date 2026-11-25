package io.github.sy220284.codexandroid

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Read only actual local images in the application-owned workspace and attachment storage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeTimelineMedia(item: TimelineItem, raw: JSONObject?, workspaceRoot: String?) {
    if (raw == null) return
    val context = LocalContext.current
    val candidates = remember(raw) {
        when (item.kind) {
            "user" -> {
                val parts = raw.optJSONArray("content")
                (0 until (parts?.length() ?: 0)).mapNotNull {
                    parts?.optJSONObject(it)?.takeIf { part -> part.optString("type") == "localImage" }?.optString("path")
                }
            }
            "imageGeneration" -> listOf(raw.optString("savedPath"))
            "imageView" -> listOf(raw.optString("path"))
            else -> emptyList()
        }.filter { it.isNotBlank() && it != "null" }
    }
    var preview by remember(item.id, workspaceRoot, candidates) { mutableStateOf<String?>(null) }
    val resolved by key(item.id, workspaceRoot, candidates) {
        produceCodexUiState<List<String>?>(null) {
            value = withContext(Dispatchers.IO) {
            val workspace = workspaceRoot?.let { File(it).canonicalFile }
            val legacy = WorkspaceStorage.legacyRoot(context)
            val roots = listOfNotNull(context.filesDir.canonicalFile, context.cacheDir.canonicalFile, workspace)
            candidates.mapNotNull { path ->
                try {
                    val original = File(path).canonicalFile
                    val file = workspace?.let { WorkspaceSelectionContract.remap(legacy, it, original) } ?: original
                    file.takeIf { it.isFile && roots.any { root ->
                        it.path.startsWith(root.path + File.separator)
                    } }?.absolutePath
                } catch (_: java.io.IOException) { null }
                catch (_: IllegalArgumentException) { null }
            }.distinct()
            }
        }
    }
    val paths = resolved
    if (paths == null) {
        if (candidates.isNotEmpty()) Text("正在读取图片…", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
        return
    }
    if (candidates.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            paths.take(3).forEach { path -> key(path) {
                val image by rememberAttachmentImage(path, 320)
                val info = image.info
                when {
                    !image.loaded -> AtlasOperationProgress("正在读取图片")
                    info == null -> Text("图片预览不可用", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    else -> Column {
                        Image(info.bitmap, "查看原图", Modifier.size(86.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { preview = path })
                        if (item.kind == "imageGeneration")
                            Text("${info.width} × ${info.height}", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                    }
                }
            } }
        }
        if (paths.size < candidates.size) Text("部分原图已不存在或不在本机工作区",
            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
    }
    if (item.kind == "imageGeneration") ImageGenerationPresentation.summary(raw)?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.muted)
    }
    preview?.let { path ->
        AttachmentPreviewDialog(paths, path, onDismiss = { preview = null }, onRemove = null)
    }
}
