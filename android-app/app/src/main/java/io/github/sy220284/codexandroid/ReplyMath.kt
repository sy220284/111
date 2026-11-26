package io.github.sy220284.codexandroid

/** Safe, bounded subset of TeX. Unsupported constructs retain literal Markdown. */
internal sealed interface ReplyMath {
    data class Text(val value: String) : ReplyMath
    data class Sequence(val parts: List<ReplyMath>) : ReplyMath
    data class Fraction(val numerator: ReplyMath, val denominator: ReplyMath) : ReplyMath
    data class Root(val value: ReplyMath, val index: ReplyMath? = null) : ReplyMath
    data class Matrix(val rows: List<List<ReplyMath>>, val left: String, val right: String) : ReplyMath
    data class Delimited(val left: String, val value: ReplyMath, val right: String) : ReplyMath
    data class Accent(val kind: String, val value: ReplyMath) : ReplyMath
    data class Script(val base: ReplyMath, val superscript: ReplyMath?, val subscript: ReplyMath?) : ReplyMath

    companion object {
        private val symbols = mapOf("alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "sigma" to "σ", "omega" to "ω",
            "Delta" to "Δ", "Sigma" to "Σ", "infty" to "∞", "times" to "×", "cdot" to "·",
            "div" to "÷", "pm" to "±", "leq" to "≤", "geq" to "≥", "neq" to "≠",
            "approx" to "≈", "sum" to "∑", "prod" to "∏", "int" to "∫", "to" to "→",
            "partial" to "∂", "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log",
            "ln" to "ln", "lim" to "lim", "quad" to " ", "qquad" to "  ",
            "iint" to "∬", "iiint" to "∭", "oint" to "∮", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
            "in" to "∈", "notin" to "∉", "subseteq" to "⊆", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
            "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "rightarrow" to "→", "leftarrow" to "←",
            "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱", "ell" to "ℓ", "hbar" to "ℏ",
            "epsilon" to "ε", "varepsilon" to "ϵ", "phi" to "φ", "varphi" to "ϕ", "rho" to "ρ", "tau" to "τ",
            "psi" to "ψ", "chi" to "χ", "xi" to "ξ", "zeta" to "ζ", "eta" to "η", "Omega" to "Ω",
            "Gamma" to "Γ", "Theta" to "Θ", "Pi" to "Π", "Lambda" to "Λ", "Psi" to "Ψ",
            "langle" to "⟨", "rangle" to "⟩", "lvert" to "|", "rvert" to "|", "Vert" to "‖")
        fun parse(source: String): ReplyMath? {
            if (source.isBlank() || source.length > 4096) return null
            return try { Parser(source).parse() } catch (_: IllegalArgumentException) { null }
        }
        fun linear(value: ReplyMath): String = when (value) {
            is Text -> value.value
            is Sequence -> value.parts.joinToString("") { linear(it) }
            is Fraction -> "(" + linear(value.numerator) + ")/(" + linear(value.denominator) + ")"
            is Root -> (value.index?.let { linear(it) + "√" } ?: "√") + "(" + linear(value.value) + ")"
            is Matrix -> value.left + value.rows.joinToString("; ") { row -> row.joinToString(", ") { linear(it) } } + value.right
            is Delimited -> value.left + linear(value.value) + value.right
            is Accent -> value.kind + "(" + linear(value.value) + ")"
            is Script -> linear(value.base) + (value.superscript?.let { "^(" + linear(it) + ")" } ?: "") +
                (value.subscript?.let { "_(" + linear(it) + ")" } ?: "")
        }
        private class Parser(private val source: String) {
            private var pos = 0
            private var atoms = 0
            fun parse(): ReplyMath = sequence(0).also { require(pos == source.length) }
            private fun peek(text: String) = source.startsWith(text, pos)
            private fun consume(text: String) { require(peek(text)); pos += text.length }
            private fun sequence(depth: Int, stop: () -> Boolean = { false }): ReplyMath {
                require(depth <= 16)
                val parts = mutableListOf<ReplyMath>()
                while (pos < source.length && source[pos] != '}' && !stop()) {
                    var base = atom(depth)
                    var sup: ReplyMath? = null
                    var sub: ReplyMath? = null
                    while (pos < source.length && source[pos] in "^_") {
                        val op = source[pos++]
                        if (op == '^') { require(sup == null); sup = atom(depth + 1) }
                        else { require(sub == null); sub = atom(depth + 1) }
                    }
                    if (sup != null || sub != null) base = Script(base, sup, sub)
                    val previous = parts.lastOrNull()
                    if (previous is Text && base is Text) parts[parts.lastIndex] = Text(previous.value + base.value)
                    else parts += base
                }
                return Sequence(parts)
            }
            private fun groupName(): String {
                consume("{")
                val end = source.indexOf('}', pos)
                require(end > pos)
                return source.substring(pos, end).also { pos = end + 1 }
            }
            private fun delimiter(): String {
                while (pos < source.length && source[pos].isWhitespace()) pos++
                require(pos < source.length)
                if (source[pos] == '\\') {
                    pos++
                    if (pos < source.length && source[pos] in "{}|") return source[pos++].toString()
                    val start = pos
                    while (pos < source.length && source[pos].isLetter()) pos++
                    return requireNotNull(symbols[source.substring(start, pos)])
                }
                val ch = source[pos++]
                require(ch in "()[]|.<>")
                return if (ch == '.') "" else ch.toString()
            }
            private fun matrix(depth: Int): ReplyMath {
                val env = groupName()
                val borders = when (env) {
                    "matrix", "aligned", "gathered" -> "" to ""
                    "pmatrix" -> "(" to ")"
                    "bmatrix" -> "[" to "]"
                    "Bmatrix" -> "{" to "}"
                    "vmatrix" -> "|" to "|"
                    "Vmatrix" -> "‖" to "‖"
                    "cases" -> "{" to ""
                    else -> throw IllegalArgumentException("不支持的公式环境")
                }
                val end = "\\end{$env}"
                val rows = mutableListOf<List<ReplyMath>>()
                var cells = mutableListOf<ReplyMath>()
                while (true) {
                    require(rows.size < 16 && cells.size < 12)
                    cells += sequence(depth + 1) { peek("&") || peek("\\\\") || peek(end) }
                    when {
                        peek(end) -> { consume(end); rows += cells; break }
                        peek("&") -> consume("&")
                        peek("\\\\") -> { consume("\\\\"); rows += cells; cells = mutableListOf() }
                        else -> throw IllegalArgumentException("公式环境未闭合")
                    }
                }
                if (env !in setOf("aligned", "gathered")) require(rows.all { it.size == rows.first().size })
                return Matrix(rows, borders.first, borders.second)
            }
            private fun atom(depth: Int): ReplyMath {
                require(depth <= 16 && pos < source.length && ++atoms <= 2048)
                val c = source[pos++]
                return when (c) {
                    '{' -> sequence(depth + 1).also { require(pos < source.length && source[pos++] == '}') }
                    '\\' -> {
                        if (pos < source.length && source[pos] in "{}%#_&$,;! ") {
                            val escaped = source[pos++]
                            return Text(if (escaped in ",;! ") " " else escaped.toString())
                        }
                        val start = pos
                        while (pos < source.length && source[pos].isLetter()) pos++
                        require(pos > start)
                        when (val command = source.substring(start, pos)) {
                            "frac", "dfrac", "tfrac" -> Fraction(atom(depth + 1), atom(depth + 1))
                            "sqrt" -> {
                                val index = if (peek("[")) {
                                    consume("["); sequence(depth + 1) { peek("]") }.also { consume("]") }
                                } else null
                                Root(atom(depth + 1), index)
                            }
                            "begin" -> matrix(depth + 1)
                            "left" -> {
                                val left = delimiter()
                                val value = sequence(depth + 1) { peek("\\right") }
                                consume("\\right")
                                Delimited(left, value, delimiter())
                            }
                            "overline", "underline", "hat", "bar", "vec", "dot", "ddot" ->
                                Accent(command, atom(depth + 1))
                            "text", "mathrm", "mathbf", "mathit", "operatorname", "mathcal", "mathbb" -> atom(depth + 1)
                            "limits", "nolimits", "displaystyle", "textstyle" -> Text("")
                            else -> Text(requireNotNull(symbols[command]))
                        }
                    }
                    '}', '^', '_', '$', '&' -> throw IllegalArgumentException("不支持的公式结构")
                    else -> Text(c.toString())
                }
            }
        }
    }
}
