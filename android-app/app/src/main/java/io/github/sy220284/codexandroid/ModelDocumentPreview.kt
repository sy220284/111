package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModelPreviewTray(requests: List<ModelPreviewRequest>, onOpen: (ModelPreviewRequest) -> Unit,
    onDismiss: (ModelPreviewRequest) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CodexTextAction(onClick = { show = true }, modifier = Modifier.weight(1f).testTag("model-preview-tray")) {
            Text("任务文件预览 · ${requests.size} 项")
        }
    }
    if (show) CodexDetailSheet("任务文件预览", { show = false }) {
        Text("以下文件由当前任务建议展示。选择后在本机读取；文件内容不会通过预览工具返回给模型。", color = Ink.muted)
        for (request in requests) key(request.id) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text(request.relativePath, style = MaterialTheme.typography.titleSmall)
                request.line?.let { Text("第 $it 行", color = Ink.muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodexSecondaryButton(onClick = { show = false; onOpen(request) }) { Text("打开预览") }
                    CodexTextAction(onClick = { onDismiss(request) }) { Text("移除") }
                }
            }
        }
    }
}

private sealed interface DocumentLoad {
    data object Loading : DocumentLoad
    data class TextFile(val source: DocumentSourceSnapshot) : DocumentLoad
    data class ImageFile(val image: ImageBitmap, val width: Int, val height: Int) : DocumentLoad
    data class Failure(val message: String) : DocumentLoad
}

private fun load(root: File, request: ModelPreviewRequest): DocumentLoad {
    val bytes = ModelPreviewPolicy.read(root, request)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "图片无法解码" }
        return DocumentLoad.ImageFile(image.asImageBitmap(), bounds.outWidth, bounds.outHeight)
    }
    val content = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { error("此文件格式需要使用专用应用打开") }
    require(!content.contains('\u0000')) { "此文件格式需要使用专用应用打开" }
    return DocumentLoad.TextFile(DocumentSourceSnapshot.create(content, request.line))
}

@Composable
internal fun ModelDocumentDialog(request: ModelPreviewRequest, root: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize()) {
            ModelDocumentPane(request, root, onDismiss, Modifier.fillMaxSize().safeDrawingPadding())
        }
    }
}

@Composable
internal fun ModelDocumentPane(request: ModelPreviewRequest, root: File, onDismiss: () -> Unit,
    modifier: Modifier = Modifier) {
    var revision by remember(request.id) { mutableIntStateOf(0) }
    var loaded by remember(request.id) { mutableStateOf<DocumentLoad>(DocumentLoad.Loading) }
    LaunchedEffect(request, revision) {
        loaded = DocumentLoad.Loading
        loaded = try { withContext(Dispatchers.IO) { load(root, request) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { DocumentLoad.Failure(failure.message ?: "无法读取文件") }
    }
    Surface(color = Ink.elevated, modifier = modifier.testTag("model-document-pane")) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(request.relativePath, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(request.line?.let { "定位第 $it 行 · 只读预览" } ?: "只读预览", color = Ink.muted,
                        style = MaterialTheme.typography.labelSmall)
                }
                CodexIconAction(onClick = { revision++ }) { CodexIcon(CodexGlyph.Retry, "重新读取文件") }
                CodexIconAction(onClick = onDismiss) { CodexIcon(CodexGlyph.Close, "关闭文件预览") }
            }
            when (val current = loaded) {
                DocumentLoad.Loading -> AtlasOperationProgress("正在读取工作区文件")
                is DocumentLoad.Failure -> CodexRecoveryState("无法打开预览", PresentationPolicy.readableError(current.message),
                    "重新读取", { revision++ })
                is DocumentLoad.ImageFile -> {
                    Text("${current.width} × ${current.height} 像素", color = Ink.muted)
                    Image(current.image, "工作区图片", modifier = Modifier.fillMaxWidth().weight(1f))
                }
                is DocumentLoad.TextFile -> DocumentSourcePane(current.source, Modifier.weight(1f))
            }
        }
    }
}

internal data class DocumentSourceSnapshot(val text: String, val chunks: List<String>, val focus: Int?,
    val line: Int?, val highlightLength: Int) {
    companion object {
        fun create(text: String, line: Int?): DocumentSourceSnapshot {
            var offset = 0
            if (line != null) {
                var current = 1
                while (current < line && offset >= 0) {
                    val newline = text.indexOf('\n', offset)
                    offset = if (newline < 0) -1 else newline + 1
                    current++
                }
            }
            if (line == null || offset < 0) return DocumentSourceSnapshot(text,
                text.chunked(2048).ifEmpty { listOf("") }, null, line, 0)
            val before = text.substring(0, offset).chunked(2048)
            val after = text.substring(offset).chunked(2048).ifEmpty { listOf("") }
            val end = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
            return DocumentSourceSnapshot(text, before + after, before.size, line, end - offset)
        }
    }
}

@Composable
internal fun DocumentSourcePane(source: DocumentSourceSnapshot, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    var search by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) { source.focus?.let { list.scrollToItem(it) } }
    if (source.line != null && source.focus == null) Text("指定行已不存在，显示当前文件内容", color = Ink.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val copy = rememberConversationCopy()
        CodexTextAction(onClick = { copy(source.text, CopyContentFormat.Literal) },
            enabled = source.text.isNotBlank()) { Text("复制全文") }
        CodexTextAction(onClick = { search = true }) { Text("全文检索") }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 20.dp)) {
        itemsIndexed(source.chunks) { index, chunk ->
            val highlight = Ink.copper
            val content = remember(index, chunk, source, highlight) {
                buildAnnotatedString {
                    append(chunk)
                    val focus = source.focus
                    if (focus != null && index >= focus) {
                        val end = (source.highlightLength - (index - focus) * 2048).coerceIn(0, chunk.length)
                        if (end > 0) addStyle(SpanStyle(background = highlight.copy(alpha = .25f)), 0, end)
                    }
                }
            }
            SelectionContainer {
                Text(content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (search) FullTextReaderDialog("文件全文", source.text) { search = false }
}
