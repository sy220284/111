package io.github.sy220284.codexandroid

/** Safe, bounded subset of TeX. Unsupported constructs retain literal Markdown. */
internal sealed interface ReplyMath {
    data class Text(val value: String) : ReplyMath
    data class Sequence(val parts: List<ReplyMath>) : ReplyMath
    data class Fraction(val numerator: ReplyMath, val denominator: ReplyMath) : ReplyMath
    data class Root(val value: ReplyMath) : ReplyMath
    data class Script(val base: ReplyMath, val superscript: ReplyMath?, val subscript: ReplyMath?) : ReplyMath

    companion object {
        private val symbols = mapOf("alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "sigma" to "σ", "omega" to "ω",
            "Delta" to "Δ", "Sigma" to "Σ", "infty" to "∞", "times" to "×", "cdot" to "·",
            "div" to "÷", "pm" to "±", "leq" to "≤", "geq" to "≥", "neq" to "≠",
            "approx" to "≈", "sum" to "∑", "prod" to "∏", "int" to "∫", "to" to "→",
            "partial" to "∂", "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log",
            "ln" to "ln", "lim" to "lim", "quad" to " ", "qquad" to "  ")
        fun parse(source: String): ReplyMath? {
            if (source.isBlank() || source.length > 512) return null
            return try { Parser(source).parse() } catch (_: IllegalArgumentException) { null }
        }
        fun linear(value: ReplyMath): String = when (value) {
            is Text -> value.value
            is Sequence -> value.parts.joinToString("") { linear(it) }
            is Fraction -> "(" + linear(value.numerator) + ")/(" + linear(value.denominator) + ")"
            is Root -> "√(" + linear(value.value) + ")"
            is Script -> linear(value.base) + (value.superscript?.let { "^(" + linear(it) + ")" } ?: "") +
                (value.subscript?.let { "_(" + linear(it) + ")" } ?: "")
        }
        private class Parser(private val source: String) {
            private var pos = 0
            fun parse(): ReplyMath = sequence(0).also { require(pos == source.length) }
            private fun sequence(depth: Int): ReplyMath {
                require(depth <= 16)
                val parts = mutableListOf<ReplyMath>()
                while (pos < source.length && source[pos] != '}') {
                    var base = atom(depth)
                    var sup: ReplyMath? = null
                    var sub: ReplyMath? = null
                    while (pos < source.length && source[pos] in "^_") {
                        val op = source[pos++]
                        if (op == '^') { require(sup == null); sup = atom(depth + 1) }
                        else { require(sub == null); sub = atom(depth + 1) }
                    }
                    if (sup != null || sub != null) base = Script(base, sup, sub)
                    parts += base
                }
                return Sequence(parts)
            }
            private fun atom(depth: Int): ReplyMath {
                require(depth <= 16 && pos < source.length)
                val c = source[pos++]
                return when (c) {
                    '{' -> sequence(depth + 1).also { require(pos < source.length && source[pos++] == '}') }
                    '\\' -> {
                        val start = pos
                        while (pos < source.length && source[pos].isLetter()) pos++
                        require(pos > start)
                        when (val command = source.substring(start, pos)) {
                            "frac" -> Fraction(atom(depth + 1), atom(depth + 1))
                            "sqrt" -> Root(atom(depth + 1))
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
