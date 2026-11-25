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
    private const val FOLDER = "codex-turn-images"

    fun directory(context: Context): File = File(context.filesDir, FOLDER)

    suspend fun importFrom(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri)
        val ext = when (type) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("仅支持 JPEG、PNG、WebP 图片")
        }
        val folder = directory(context).apply { check(exists() || mkdirs()) { "无法建立图片缓存目录" } }
        val output = File(folder, UUID.randomUUID().toString() + "." + ext)
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
                        require(total <= MAX_BYTES) { "单张图片上限 12 MB" }
                        destination.write(buffer, 0, count)
                    }
                    require(total > 0L) { "图片内容为空" }
                }
            }
            // Validate the actual file signature after copying, not just
            // the provider's MIME label. Unsupported/corrupt files stay local.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片文件无法解码" }
            output.canonicalPath
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
        if (target.exists() && !AttachmentRetentionPolicy.isHistorical(folder, target))
            check(target.delete()) { "无法清理图片附件" }
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
        Text("待发送图片 · ${paths.size}/${LocalImageAttachments.MAX_FILES}",
            style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            paths.forEachIndexed { idx, path ->
                key(path) {
                    val image by rememberAttachmentImage(path, 160)
                    val thumbnail = image.bitmap
                    Surface(shape = RoundedCornerShape(13.dp),
                        color = Ink.panel,
                        border = BorderStroke(1.dp, Ink.border)) {
                        Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(9.dp))
                                    .clickable { previewPath = path }
                                    .semantics { contentDescription = "预览附件图片 ${idx + 1}" },
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
                                Text("图片 ${idx + 1}", style = MaterialTheme.typography.labelMedium)
                                Text("${File(path).length() / 1024} KB",
                                    color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                            }
                            CodexIconAction(onClick = { onRemove(path) }, modifier = Modifier.size(48.dp)) {
                                CodexIcon(CodexGlyph.Close, contentDescription = "移除图片 ${idx + 1}",
                                    tint = Ink.red)
                            }
                        }
                    }
                }
            }
        }
        previewPath?.takeIf { it in paths }?.let { path ->
            AttachmentPreviewDialog(paths, path,
                onDismiss = { previewPath = null }, onRemove = {
                    onRemove(it)
                    previewPath = null
                })
        }
    }
}
