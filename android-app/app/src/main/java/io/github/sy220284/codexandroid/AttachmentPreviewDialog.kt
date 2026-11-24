package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本地附件全屏查看：保留真实图片、支持缩放、平移、切换和移除。 */
@Composable
internal fun AttachmentPreviewDialog(paths: List<String>, path: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onRemove: (String) -> Unit) {
    val index = paths.indexOf(path)
    if (index < 0) return
    var retry by remember(path) { mutableIntStateOf(0) }
    val preview by key(path, retry) {
        produceState<Pair<Boolean, ImageBitmap?>>(false to null) {
            value = true to withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                BitmapFactory.decodeFile(path,
                    BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize().testTag("attachment-preview")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ArrowBack, "关闭图片预览")
                    }
                    Text("图片 ${index + 1} / ${paths.size}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { onRemove(path) }, modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.DeleteOutline, "移除当前图片", tint = Ink.red)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                    val bitmap = preview.second
                    when {
                        !preview.first -> AtlasOperationProgress("正在载入图片")
                        bitmap == null -> CodexRecoveryState("图片读取失败", "附件可能已被移动或损坏。",
                            "重新读取", { retry++ })
                        else -> Image(bitmap, "当前附件的大图预览",
                            modifier = Modifier.fillMaxSize().testTag("attachment-preview-image")
                                .semantics { stateDescription = "缩放 ${(scale * 100).toInt()}%" }
                                .pointerInput(path, bitmap) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val next = (scale * zoom).coerceIn(1f, 4f)
                                        val fit = minOf(size.width.toFloat() / bitmap.width,
                                            size.height.toFloat() / bitmap.height)
                                        val x = ((bitmap.width * fit * next - size.width) / 2).coerceAtLeast(0f)
                                        val y = ((bitmap.height * fit * next - size.height) / 2).coerceAtLeast(0f)
                                        offset = Offset((offset.x + pan.x).coerceIn(-x, x),
                                            (offset.y + pan.y).coerceIn(-y, y))
                                        scale = next
                                    }
                                }.pointerInput(path) {
                                    detectTapGestures(onDoubleTap = {
                                        scale = if (scale > 1f) 1f else 2f
                                        offset = Offset.Zero
                                    })
                                }.graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    translationX = offset.x; translationY = offset.y
                                },
                            contentScale = ContentScale.Fit)
                    }
                }
                if (preview.second != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = {
                            scale = (scale / 1.5f).coerceAtLeast(1f)
                            offset = Offset.Zero
                        }, enabled = scale > 1f, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("attachment-zoom-out")) { Text("缩小") }
                        Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp).testTag("attachment-zoom-level"))
                        TextButton(onClick = {
                            scale = (scale * 1.5f).coerceAtMost(4f)
                            offset = Offset.Zero
                        }, enabled = scale < 4f, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("attachment-zoom-in")) { Text("放大") }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { onSelect(paths[index - 1]) }, enabled = index > 0,
                        modifier = Modifier.size(48.dp)) { CodexIcon(CodexGlyph.ArrowBack, "上一张") }
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero },
                        enabled = preview.second != null, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("attachment-preview-reset")) { Text("恢复原始大小") }
                    IconButton(onClick = { onSelect(paths[index + 1]) }, enabled = index < paths.lastIndex,
                        modifier = Modifier.size(48.dp)) { CodexIcon(CodexGlyph.ArrowForward, "下一张") }
                }
            }
        }
    }
}
