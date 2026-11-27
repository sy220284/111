package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Bounded lexical highlighting; exact code text and copy semantics remain unchanged. */
internal object ReplyCodeHighlight {
    private val languages = setOf("kt", "kotlin", "java", "js", "javascript", "ts", "typescript",
        "py", "python", "rs", "rust", "go", "c", "cpp", "json", "sh", "bash", "sql")
    private val tokens = Regex("""(?m)(//[^\n]*|#[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:fun|val|var|class|object|interface|return|if|else|when|for|while|import|package|const|let|function|async|await|def|from|try|catch|throw|match|impl|struct|enum|pub|use|fn|true|false|null|None|True|False|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE)\b|\b\d+(?:\.\d+)?\b)""")
    enum class Kind { Comment, Literal, Keyword }
    data class Token(val start: Int, val end: Int, val kind: Kind)

    fun prepare(source: String, language: String): List<Token> {
        if (source.length > 65536 || language.lowercase() !in languages) return emptyList()
        return tokens.findAll(source).map { match ->
            val value = match.value
            val kind = when {
                value.startsWith("//") || value.startsWith("#") -> Kind.Comment
                value.startsWith("\"") || value.startsWith("'") -> Kind.Literal
                else -> Kind.Keyword
            }
            Token(match.range.first, match.range.last + 1, kind)
        }.toList()
    }

    @Composable
    fun render(source: String, prepared: List<Token>): androidx.compose.ui.text.AnnotatedString {
        val muted = Ink.muted
        val literal = Ink.jade
        val keyword = Ink.copper
        return remember(source, prepared, muted, literal, keyword) {
            buildAnnotatedString {
                append(source)
                for (token in prepared) addStyle(SpanStyle(color = when (token.kind) {
                    Kind.Comment -> muted
                    Kind.Literal -> literal
                    Kind.Keyword -> keyword
                }), token.start, token.end)
            }
        }
    }
}
