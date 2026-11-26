package io.github.sy220284.codexandroid

/** Pure document preparation. Each committed block owns the same inline tokens
 * used by display and selection, and rich math is prepared off the UI thread.
 * An engine is confined to one serial background collector. */
internal data class PreparedReplyBlock(
    val block: MessageBlock,
    val inline: Map<String, List<MessageMarkdown.CopyPart>>,
    val math: Map<String, ReplyMath?> = emptyMap(),
    val codeTokens: List<ReplyCodeHighlight.Token> = emptyList()
) {
    companion object {
        fun prepare(block: MessageBlock): PreparedReplyBlock {
            val text = when (block.kind) {
                "table" -> block.rows.flatten() + ""
                "rule", "code", "stream-tail", in RichReplyContent.kinds -> emptyList()
                else -> listOf(block.text)
            }
            return PreparedReplyBlock(block, text.distinct().associateWith(MessageMarkdown::copyParts),
                if (block.kind == "math") block.rows.associate { it[0] to ReplyMath.parse(it[0]) }
                else emptyMap(),
                if (block.kind == "code") ReplyCodeHighlight.prepare(block.text, block.label) else emptyList())
        }
        fun tail(text: String) = PreparedReplyBlock(MessageBlock("stream-tail", text), emptyMap())
    }
}
internal class ReplyDocumentEngine {
    data class Document(val prefix: String, val blocks: PersistentSequence<PreparedReplyBlock>,
        val stable: Boolean, val reusedBlockCount: Int = 0)
    private var previous = Document("", PersistentSequence(), true)

    fun prepare(source: String, streaming: Boolean): Document {
        val reusable = previous.stable && source.startsWith(previous.prefix)
        val start = if (reusable) previous.prefix.length else 0
        val end = if (streaming) MessageMarkdown.stablePrefixLength(source, start) else source.length
        if (reusable && end == start && (streaming || previous.stable)) return previous
        val prefix = source.substring(0, end)
        if (!streaming && prefix == previous.prefix) return previous
        val suffix = MessageMarkdown.blocks(source.substring(start, end)).map(PreparedReplyBlock::prepare)
        var blocks = if (reusable) previous.blocks else PersistentSequence()
        val reusedCount = blocks.size
        for (block in suffix) blocks = blocks.append(block)
        return Document(prefix, blocks, streaming, reusedCount).also { previous = it }
    }
}
