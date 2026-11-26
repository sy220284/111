package io.github.sy220284.codexandroid

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * SAF content URIs are copied into persistent app-private storage. The Codex native
 * subprocess runs under the same UID and can read the resulting localImage path.
 */
object LocalImageAttachments {
    const val MAX_FILES = 3
    private const val MAX_BYTES = 12L * 1024 * 1024
    private const val MAX_AUDIO_BYTES = 50L * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
    private const val FOLDER = "codex-turn-images"

    fun directory(context: Context): File = File(context.filesDir, FOLDER)

    suspend fun importFrom(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri)
        val ext = when (type) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/webm" -> "webm"
            "audio/ogg", "application/ogg" -> "ogg"
            "video/mp4" -> "mp4"
            else -> throw IllegalArgumentException("请选择 JPEG、PNG、WebP、WAV、MP3、M4A、OGG、WebM 音频或 MP4 视频")
        }
        val folder = directory(context).apply { check(exists() || mkdirs()) { "无法建立图片缓存目录" } }
        val output = File(folder, UUID.randomUUID().toString() + "." + ext)
        val kind = LocalMediaAttachmentPolicy.kind(output.path)
        val limit = when (kind) { "audio" -> MAX_AUDIO_BYTES; "video" -> MAX_VIDEO_BYTES; else -> MAX_BYTES }
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开所选图片")
            source.use { input ->
                output.outputStream().use { destination ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) { "单个附件上限 ${limit / 1024 / 1024} MB" }
                        destination.write(buffer, 0, count)
                    }
                    require(total > 0L) { "图片内容为空" }
                }
            }
            // Validate the actual file signature after copying, not just
            // the provider's MIME label. Unsupported/corrupt files stay local.
            when (kind) {
                "video" -> {
                    currentCoroutineContext().ensureActive()
                    VideoAttachmentPreparation.inspect(output).canonicalPath
                }
                "audio" -> {
                    val extractor = android.media.MediaExtractor()
                    try {
                        extractor.setDataSource(output.absolutePath)
                        require((0 until extractor.trackCount).any {
                            extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        }) { "音频文件无法解码" }
                    } finally { extractor.release() }
                    output.canonicalPath
                }
                else -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(output.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片文件无法解码" }
                    output.canonicalPath
                }
            }
        } catch (failure: Exception) {
            output.delete()
            throw failure
        }
    }

    /** Prune unreferenced old files; unsent drafts always outlive maintenance. */
    suspend fun cleanExpired(context: Context, referenced: Set<String>) =
        withContext(Dispatchers.IO) {
            AttachmentRetentionPolicy.prune(directory(context), referenced)
        }

    suspend fun preserveForHistory(context: Context, paths: List<String>) {
        if (paths.isNotEmpty()) withContext(Dispatchers.IO) {
            AttachmentRetentionPolicy.preserveForHistory(directory(context), paths)
        }
    }

    /** Deletes an unsent file only; conversation history can reference a path in multiple branches. */
    suspend fun release(context: Context, path: String) = withContext(Dispatchers.IO) {
        val folder = directory(context).canonicalFile
        val target = File(path).canonicalFile
        require(target.parentFile == folder) { "图片路径不属于本应用的私有附件目录" }
        for (owned in LocalMediaAttachmentPolicy.recoveryPaths(target.path)) {
            val file = File(owned).canonicalFile
            require(file.parentFile == folder) { "附件路径不属于私有目录" }
            if (file.exists() && !AttachmentRetentionPolicy.isHistorical(folder, file))
                check(file.delete()) { "无法清理媒体附件" }
        }
    }
}

@Composable
fun LocalImageAttachmentStrip(
    paths: List<String>,
    onRemove: (String) -> Unit
) {
    if (paths.isEmpty()) return
    var previewPath by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("${if (paths.all { LocalMediaAttachmentPolicy.kind(it) == "image" }) "待发送图片" else "待发送媒体"} · ${paths.size}/${LocalImageAttachments.MAX_FILES}",
            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            paths.forEachIndexed { idx, path ->
                key(path) {
                    val kind = LocalMediaAttachmentPolicy.kind(path)
                    val preview = if (kind == "video") LocalMediaAttachmentPolicy.frames(path) else path
                    val thumbnail = if (kind == "audio") null else {
                        val image by rememberAttachmentImage(preview, 160)
                        image.bitmap
                    }
                    Surface(shape = RoundedCornerShape(13.dp),
                        color = Ink.panel,
                        border = BorderStroke(1.dp, Ink.border)) {
                        Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(9.dp))
                                    .clickable(enabled = kind != "audio" && thumbnail != null) { previewPath = preview }
                                    .semantics { contentDescription = if (kind == "image") "预览附件图片 ${idx + 1}" else "媒体附件 ${idx + 1}" },
                                contentAlignment = Alignment.Center
                            ) {
                                thumbnail?.let { image ->
                                    Image(image, contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop)
                                } ?: CodexIcon(CodexGlyph.Photo, contentDescription = null, tint = Ink.copper)
                            }
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text("${LocalMediaAttachmentPolicy.label(path)} ${idx + 1}", style = MaterialTheme.typography.labelMedium)
                                Text("${File(path).length() / 1024} KB",
                                    color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            CodexIconAction(onClick = { onRemove(path) }, modifier = Modifier.size(48.dp)) {
                                CodexIcon(CodexGlyph.Close, contentDescription = if (kind == "image") "移除图片 ${idx + 1}" else "移除媒体附件 ${idx + 1}",
                                    tint = Ink.red)
                            }
                        }
                    }
                }
            }
        }
        val previews = paths.filter { LocalMediaAttachmentPolicy.kind(it) != "audio" }
            .flatMap { owner ->
                val pictures = if (LocalMediaAttachmentPolicy.kind(owner) == "video")
                    LocalMediaAttachmentPolicy.recoveryPaths(owner).filter { it.endsWith(".jpg") && File(it).isFile }
                    else listOf(owner)
                pictures.map { it to owner }
            }.toMap()
        previewPath?.takeIf { it in previews }?.let { path ->
            AttachmentPreviewDialog(previews.keys.toList(), path,
                onDismiss = { previewPath = null }, onRemove = {
                    previews[it]?.let(onRemove)
                    previewPath = null
                })
        }
    }
}
