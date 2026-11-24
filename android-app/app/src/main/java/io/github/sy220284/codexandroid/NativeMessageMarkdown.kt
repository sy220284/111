package io.github.sy220284.codexandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal data class MessageBlock(val kind: String, val text: String, val level: Int = 0)
internal object MessageMarkdown {
    fun blocks(source: String): List<MessageBlock> {
        val result = mutableListOf<MessageBlock>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var fence: String? = null
        fun flush() {
            if (paragraph.isNotEmpty()) {
                result.add(MessageBlock("paragraph", paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        for (line in source.lines()) {
            val trimmed = line.trimStart()
            val marker = listOf("~~~", "\u0060\u0060\u0060").firstOrNull { trimmed.startsWith(it) }
            if (fence != null) {
                if (marker == fence) {
                    result.add(MessageBlock("code", code.joinToString("\n")))
                    code.clear()
                    fence = null
                } else code.add(line)
            } else if (marker != null) {
                flush()
                fence = marker
            } else if (trimmed.isBlank()) flush()
            else {
                val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
                when {
                    heading != null -> {
                        flush()
                        result.add(MessageBlock("heading", heading.groupValues[2], heading.groupValues[1].length))
                    }
                    trimmed.startsWith("|") -> { flush(); result.add(MessageBlock("table", trimmed)) }
                    trimmed.startsWith("> ") -> { flush(); result.add(MessageBlock("quote", trimmed.drop(2))) }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        flush(); result.add(MessageBlock("bullet", trimmed.drop(2)))
                    }
                    else -> paragraph.add(line)
                }
            }
        }
        flush()
        if (fence != null) result.add(MessageBlock("code", code.joinToString("\n")))
        return result
    }
    private val inline = Regex("(\\*\\*[^*]+\\*\\*|__[^_]+__|\\u0060[^\\u0060]+\\u0060|\\*[^*]+\\*)")
    fun inlineText(source: String) = buildAnnotatedString {
        var offset = 0
        for (match in inline.findAll(source)) {
            append(source.substring(offset, match.range.first))
            val part = match.value
            when {
                part.startsWith("**") || part.startsWith("__") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.drop(2).dropLast(2)) }
                part.startsWith('\u0060') ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.drop(1).dropLast(1)) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.drop(1).dropLast(1)) }
            }
            offset = match.range.last + 1
        }
        append(source.substring(offset))
    }
}

@Composable
internal fun NativeMessageMarkdown(source: String) {
    val blocks = remember(source) { MessageMarkdown.blocks(source) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block.kind) {
                    "code", "table" -> Text(block.text,
                        Modifier.fillMaxWidth().background(Ink.recessed, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState()).padding(10.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    "heading" -> Text(MessageMarkdown.inlineText(block.text), fontWeight = FontWeight.SemiBold,
                        style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                    "bullet" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = Ink.muted)
                        Text(MessageMarkdown.inlineText(block.text), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                    "quote" -> Text(MessageMarkdown.inlineText(block.text), Modifier.padding(start = 12.dp),
                        color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(MessageMarkdown.inlineText(block.text), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
