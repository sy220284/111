package io.github.sy220284.codexandroid

import androidx.compose.foundation.Image
import androidx.compose.ui.viewinterop.AndroidView
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
                    parts?.optJSONObject(it)?.takeIf { part -> part.optString("type") in setOf("localImage", "localAudio") }?.optString("path")
                }
            }
            "imageGeneration" -> listOf(raw.optString("savedPath"))
            "imageView" -> listOf(raw.optString("path"))
            else -> emptyList()
        }.filter { it.isNotBlank() && it != "null" }
    }
    var preview by remember(item.id, workspaceRoot, candidates) { mutableStateOf<String?>(null) }
    var playback by remember(item.id, workspaceRoot, candidates) { mutableStateOf<String?>(null) }
    val hasWireMedia = raw.optJSONArray("content")?.let { parts ->
        (0 until parts.length()).any { parts.optJSONObject(it)?.optString("type") in setOf("localImage", "localAudio", "image", "audio") }
    } == true
    val clientId = raw.optString("clientId").takeIf { hasWireMedia && it.isNotBlank() && it != "null" }
    val original by loadCodexUiState<Result<List<String>>?>(null, item.id, clientId) {
        codexLoadResult { MediaHistoryStore.read(LocalImageAttachments.directory(context), clientId) }
    }
    val originalPaths = original?.getOrNull().orEmpty()
    val shownCandidates = if (originalPaths.isNotEmpty()) originalPaths else candidates
    val resolved by key(item.id, workspaceRoot, shownCandidates) {
        loadCodexUiState<List<String>?>(null, item.id, workspaceRoot, shownCandidates) {
            val workspace = workspaceRoot?.let { File(it).canonicalFile }
            val legacy = WorkspaceStorage.legacyRoot(context)
            val roots = listOfNotNull(context.filesDir.canonicalFile, context.cacheDir.canonicalFile, workspace)
            shownCandidates.mapNotNull { path ->
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
    val paths = resolved
    if (original == null || paths == null) {
        if (candidates.isNotEmpty() || clientId != null) Text("正在读取媒体…", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
        return
    }
    if (original?.isFailure == true) Text("原始媒体记录无法读取，保留原生消息资料", color = Ink.muted, style = MaterialTheme.typography.labelSmall)
    if (hasWireMedia && shownCandidates.isEmpty()) Text("该历史媒体由内核保存为内嵌数据，无法定位本机原件", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
    if (shownCandidates.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            paths.take(3).forEach { path -> key(path) {
                val extension = File(path).extension.lowercase(java.util.Locale.ROOT)
                if (extension in setOf("wav", "mp3", "m4a", "ogg", "webm", "mp4")) {
                    Column {
                        Text(if (extension == "mp4") "视频附件" else "音频附件", style = MaterialTheme.typography.labelMedium)
                        CodexTextAction(onClick = { playback = path }) { Text("播放原件") }
                    }
                } else {
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
                }
            } }
        }
        if (paths.size < shownCandidates.size) Text("部分原始媒体已不存在或不在本机工作区",
            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
    }
    if (item.kind == "imageGeneration") ImageGenerationPresentation.summary(raw)?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.muted)
    }
    preview?.let { path ->
        AttachmentPreviewDialog(paths.filter { File(it).extension.lowercase(java.util.Locale.ROOT) !in setOf("wav", "mp3", "m4a", "ogg", "webm", "mp4") }, path, onDismiss = { preview = null }, onRemove = null)
    }
    playback?.let { path -> LocalHistoryPlayback(path) { playback = null } }
}

@Composable
private fun LocalHistoryPlayback(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var failure by remember(path) { mutableStateOf(false) }
    val player = remember(path) { android.widget.VideoView(context) }
    DisposableEffect(player) {
        onDispose { player.stopPlayback() }
    }
    CodexDialogFrame(onDismissRequest = onDismiss,
        title = { Text(if (path.endsWith(".mp4", true)) "视频原件" else "音频原件") },
        text = {
            Column {
                if (failure) Text("本机播放器无法读取此媒体")
                AndroidView(factory = {
                    player.apply {
                        setMediaController(android.widget.MediaController(context).also { it.setAnchorView(this) })
                        setOnErrorListener { _, _, _ -> failure = true; true }
                        setOnPreparedListener { start() }
                        setVideoPath(path)
                    }
                }, modifier = Modifier.fillMaxWidth().height(220.dp))
            }
        },
        confirmButton = { CodexTextAction(onClick = onDismiss) { Text("关闭") } })
}
