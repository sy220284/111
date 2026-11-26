package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** Bounded lexical highlighting; exact code text and copy semantics remain unchanged. */
internal object ReplyCodeHighlight {
    private val languages = setOf("kt", "kotlin", "java", "js", "javascript", "ts", "typescript",
        "py", "python", "rs", "rust", "go", "c", "cpp", "json", "sh", "bash", "sql")
    private val tokens = Regex("""(?m)(//[^\n]*|#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:fun|val|var|class|object|interface|return|if|else|when|for|while|import|package|const|let|function|async|await|def|from|try|catch|throw|match|impl|struct|enum|pub|use|fn|true|false|null|None|True|False|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE)\b|\b\d+(?:\.\d+)?\b)""")
    @Composable
    fun render(source: String, language: String): androidx.compose.ui.text.AnnotatedString {
        val muted = Ink.muted
        val literal = Ink.jade
        val keyword = Ink.copper
        return buildAnnotatedString {
        if (source.length > 65536 || language.lowercase() !in languages) append(source)
        else {
            var offset = 0
            for (match in tokens.findAll(source)) {
                append(source.substring(offset, match.range.first))
                val value = match.value
                val color = when {
                    value.startsWith("//") || value.startsWith("#") -> muted
                    value.startsWith("\"") || value.startsWith("'") -> literal
                    else -> keyword
                }
                withStyle(SpanStyle(color = color)) { append(value) }
                offset = match.range.last + 1
            }
            append(source.substring(offset))
        }
    }
}
}
