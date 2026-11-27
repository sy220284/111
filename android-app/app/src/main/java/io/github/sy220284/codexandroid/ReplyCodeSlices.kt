package io.github.sy220284.codexandroid

/** Bound code layout by both characters and lines, preserving absolute source offsets.
 * Tokens crossing boundaries are clipped rather than re-parsed. */
internal object ReplyCodeSlices {
    data class Slice(val start: Int, val text: String, val tokens: List<ReplyCodeHighlight.Token>)
    fun prepare(source: String, tokens: List<ReplyCodeHighlight.Token>): List<Slice> {
        if (source.length <= 3200 && source.count { it == '\n' } < 32) return emptyList()
        return buildList {
            var start = 0
            var firstToken = 0
            while (start < source.length) {
                val limit = minOf(source.length, start + 3200)
                var end = start
                var lines = 0
                var lastBreak = start
                while (end < limit && lines < 32) {
                    if (source[end++] == '\n') { lines++; lastBreak = end }
                }
                if (end < source.length && lastBreak > start) end = lastBreak
                if (end < source.length && Character.isHighSurrogate(source[end - 1]) &&
                    Character.isLowSurrogate(source[end])) end--
                while (firstToken < tokens.size && tokens[firstToken].end <= start) firstToken++
                val clipped = buildList {
                    var i = firstToken
                    while (i < tokens.size && tokens[i].start < end) {
                        val token = tokens[i++]
                        add(token.copy(start = maxOf(start, token.start) - start, end = minOf(end, token.end) - start))
                    }
                }
                add(Slice(start, source.substring(start, end), clipped))
                start = end
            }
        }
    }
}
