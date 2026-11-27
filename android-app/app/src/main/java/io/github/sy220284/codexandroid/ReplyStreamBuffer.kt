package io.github.sy220284.codexandroid

/** Incremental counterpart of the Markdown stable-block boundary recognizer.
 * Newline-free tails and open fences retain the scan cursor across notifications. */
internal class ReplyStablePrefixScanner(from: Int = 0) {
    private var scanned = from
    private var lineStart = from
    private var stable = from
    private var opening: String? = null
    private var mathEnding: String? = null

    fun scan(source: CharSequence): Int {
        require(scanned <= source.length)
        while (scanned < source.length) {
            val end = scanned++
            if (source[end] != '\n') continue
            val line = source.subSequence(lineStart, end).toString().trim()
            val current = opening
            if (mathEnding != null) {
                if (line == mathEnding) mathEnding = null
            } else if (current != null) {
                if (line.length >= current.length && line.all { it == current.first() }) opening = null
            } else {
                val marker = fence.matchEntire(line)
                if (marker != null) opening = marker.groupValues[1]
                else if (line == "\$\$") mathEnding = "\$\$"
                else if (line == "\\[") mathEnding = "\\]"
                else if (line.isBlank()) stable = end + 1
            }
            lineStart = scanned
        }
        return stable
    }

    fun discardCommitted(count: Int) {
        require(count == stable && count <= lineStart)
        scanned -= count
        lineStart -= count
        stable = 0
    }

    companion object { private val fence = Regex("^(`{3,}|~{3,})(.*)$") }
}

/** Worker-confined raw tail; immutable full render chunks are reused until a
 * Markdown boundary commits them. The native source remains the copy authority. */
internal class ReplyStreamBuffer {
    private val text = StringBuilder()
    private var scanner = ReplyStablePrefixScanner()
    private var full = PersistentSequence<PreparedReplyBlock>()
    private var cursor = 0

    fun append(chunks: List<String>) { for (chunk in chunks) text.append(chunk) }

    fun commit(streaming: Boolean): String {
        val end = if (streaming) scanner.scan(text) else text.length
        if (end == 0) return ""
        val result = text.substring(0, end)
        text.delete(0, end)
        if (streaming) scanner.discardCommitted(end) else scanner = ReplyStablePrefixScanner()
        full = PersistentSequence()
        cursor = 0
        return result
    }

    fun tail(): List<PreparedReplyBlock> {
        val size = TimelineReplyPages.TEXT_CHUNK
        // Leave one look-ahead character before freezing a chunk so a newly
        // arriving low surrogate cannot split a valid Unicode pair.
        while (text.length - cursor > size) {
            var end = cursor + size
            if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            full = full.append(PreparedReplyBlock.tail(text.substring(cursor, end)))
            cursor = end
        }
        val rest = if (cursor == text.length) emptyList() else listOf(PreparedReplyBlock.tail(text.substring(cursor)))
        return TimelineJoinedList(full, rest)
    }
}
