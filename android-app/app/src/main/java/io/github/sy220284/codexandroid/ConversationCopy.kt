package io.github.sy220284.codexandroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.TextContentRenderer

internal enum class CopyContentFormat { Markdown, Literal, VisibleText }
internal data class CopyPayload(val text: String, val html: String? = null)

/** Mirrors the native Markdown/HTML clipboard pair; never embeds remote images or raw HTML. */
internal object ConversationCopyContent {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val html = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()
    private val plain = TextContentRenderer.builder().extensions(extensions).build()

    fun payload(source: String, format: CopyContentFormat): CopyPayload {
        require(source.isNotBlank()) { "没有可复制的内容" }
        if (format == CopyContentFormat.Literal) return CopyPayload(source)
        val root = parser.parse(source)
        sanitize(root)
        return if (format == CopyContentFormat.Markdown) CopyPayload(source, html.render(root))
        else CopyPayload(plain.render(root))
    }

    private fun sanitize(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            val current = child
            val next = current.next
            sanitize(current)
            when (current) {
                is Image -> {
                    val alt = buildString { appendVisible(current, this) }
                    current.insertBefore(Text(alt))
                    current.unlink()
                }
                is Link -> if (!current.destination.startsWith("https://", true) &&
                    !current.destination.startsWith("http://", true)) {
                    while (current.firstChild != null) current.insertBefore(current.firstChild)
                    current.insertBefore(Text(" (" + current.destination + ")"))
                    current.unlink()
                }
            }
            child = next
        }
    }

    private fun appendVisible(node: Node, out: StringBuilder) {
        when (node) {
            is Text -> out.append(node.literal)
            is Code -> out.append(node.literal)
            is SoftLineBreak, is HardLineBreak -> out.append(' ')
            else -> {
                var child = node.firstChild
                while (child != null) { appendVisible(child, out); child = child.next }
            }
        }
    }

    /** A selected rectangle contains only its selected cells; a synthetic blank header is
     * used when the selection starts below the real heading. Keep literal pipes escaped. */
    fun tableRange(rows: List<List<String>>, firstRow: Int, lastRow: Int,
                   firstColumn: Int, lastColumn: Int): String {
        require(firstRow in rows.indices && lastRow in firstRow until rows.size)
        require(firstColumn >= 0 && lastColumn >= firstColumn &&
            lastColumn < (rows.maxOfOrNull { it.size } ?: 0))
        val width = lastColumn - firstColumn + 1
        fun line(row: List<String>) = row.joinToString(" | ", "| ", " |") {
            it.replace(Regex("(?<!\\\\)\\|")) { "\\|" }.replace("\n", " ")
        }
        val selected = (firstRow..lastRow).map { r ->
            (firstColumn..lastColumn).map { c -> rows[r].getOrElse(c) { "" } }
        }
        return buildList {
            add(line(if (firstRow == 0) selected.first() else List(width) { "" }))
            add(line(List(width) { "---" }))
            addAll((if (firstRow == 0) selected.drop(1) else selected).map(::line))
        }.joinToString("\n")
    }
}

/** Android owns the actual clipboard. Native Rust TUI disables its Android clipboard path. */
internal object ConversationClipboard {
    suspend fun copy(context: Context, source: String, format: CopyContentFormat,
        isCurrent: () -> Boolean = { true }): Boolean {
        return try {
            val payload = withContext(Dispatchers.Default) { ConversationCopyContent.payload(source, format) }
            withContext(Dispatchers.Main.immediate) {
                check(isCurrent()) { "复制所属会话或账号已变化，请重新操作" }
                val manager = context.getSystemService(ClipboardManager::class.java)
                    ?: error("系统剪贴板不可用")
                val clip = payload.html?.let { ClipData.newHtmlText("Codex", payload.text, it) }
                    ?: ClipData.newPlainText("Codex", payload.text)
                manager.setPrimaryClip(clip)
                // Android 13+ already provides its native clipboard confirmation.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(context, "复制失败：" + (failure.message ?: "请使用文件导出"), Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}

@Composable
internal fun rememberConversationCopy(): (String, CopyContentFormat) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    return { source, format ->
        if (!busy) {
            busy = true
            scope.launch {
                try { ConversationClipboard.copy(context, source, format) }
                finally { busy = false }
            }
        } else Toast.makeText(context, "正在复制，请稍候", Toast.LENGTH_SHORT).show()
    }
}
