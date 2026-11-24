package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Read only actual local images in the application-owned workspace and attachment storage. */
@Composable
internal fun NativeTimelineMedia(item: TimelineItem, raw: JSONObject?) {
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
    var preview by remember(item.id) { mutableStateOf<String?>(null) }
    val paths by produceState<List<String>>(emptyList(), candidates) {
        value = withContext(Dispatchers.IO) {
            val roots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
            candidates.mapNotNull { path ->
                try {
                    val file = File(path).canonicalFile
                    file.takeIf { it.isFile && roots.any { root ->
                        it.path.startsWith(root.path + File.separator)
                    } }?.absolutePath
                } catch (_: java.io.IOException) { null }
            }.distinct()
        }
    }
    if (candidates.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paths.take(3).forEach { path ->
                val bitmap by produceState<ImageBitmap?>(null, path) {
                    value = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                            val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 320).coerceAtLeast(1)
                            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                        }
                    }
                }
                bitmap?.let {
                    Image(it, "查看原图", Modifier.size(86.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { preview = path })
                } ?: Text("图片预览不可用", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
            }
        }
        if (paths.size < candidates.size) Text("部分原图已不存在或不在本机工作区",
            style = MaterialTheme.typography.labelSmall, color = Ink.muted)
    }
    preview?.let { path ->
        AttachmentPreviewDialog(paths, path, onDismiss = { preview = null }, onRemove = { preview = null })
    }
}
