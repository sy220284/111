package io.github.sy220284.codexandroid

/** Pure document preparation. Each committed block owns the same inline tokens
 * used by display and selection, and rich math is prepared off the UI thread.
 * An engine is confined to one serial background collector. */
internal data class PreparedReplyBlock(
    val block: MessageBlock,
    val inline: Map<String, List<MessageMarkdown.CopyPart>>,
    val math: Map<String, ReplyMath?> = emptyMap(),
    val codeTokens: List<ReplyCodeHighlight.Token> = emptyList(),
    val codeSlices: List<ReplyCodeSlices.Slice> = emptyList(),
    val tableColumns: Int = block.rows.maxOfOrNull { it.size } ?: 0
) {
    companion object {
        fun prepare(block: MessageBlock): PreparedReplyBlock {
            val text = when (block.kind) {
                "table" -> block.rows.flatten() + ""
                "rule", "code", "stream-tail", in RichReplyContent.kinds -> emptyList()
                else -> listOf(block.text)
            }
            val tokens = if (block.kind == "code") ReplyCodeHighlight.prepare(block.text, block.label) else emptyList()
            return PreparedReplyBlock(block, text.distinct().associateWith(MessageMarkdown::copyParts),
                if (block.kind == "math") block.rows.associate { it[0] to ReplyMath.parse(it[0]) }
                else emptyMap(),
                tokens, if (block.kind == "code") ReplyCodeSlices.prepare(block.text, tokens) else emptyList())
        }
        fun tail(text: String) = PreparedReplyBlock(MessageBlock("stream-tail", text), emptyMap())
    }
}
internal class ReplyDocumentEngine {
    class Document(val prefixLength: Int, val blocks: PersistentSequence<PreparedReplyBlock>,
        val stable: Boolean, val reusedBlockCount: Int = 0, readPrefix: () -> String) {
        val prefix: String by lazy(LazyThreadSafetyMode.PUBLICATION, readPrefix)
    }
    data class StreamPreparation(val document: Document, val tail: List<PreparedReplyBlock>)
    private var previous = Document(0, PersistentSequence(), true) { "" }
    private var lastChunks: TimelineTextChunks? = null
    private var buffer = ReplyStreamBuffer()

    /** Native text snapshots share their immutable chunk chain. The normal stream path
     * scans only appended characters; no accumulated String is required by presentation. */
    fun prepareChunks(chunks: TimelineTextChunks, streaming: Boolean): StreamPreparation {
        val append = lastChunks?.takeIf { previous.stable || (!streaming && it === chunks) }
            ?.let(chunks::suffixSince)
        if (append == null) {
            buffer = ReplyStreamBuffer()
            previous = Document(0, PersistentSequence(), true) { "" }
            buffer.append(requireNotNull(chunks.suffixSince(TimelineTextChunks.from(""))))
        } else buffer.append(append)
        lastChunks = chunks
        val committed = buffer.commit(streaming)
        if (committed.isNotEmpty() || !streaming && previous.stable) {
            var blocks = previous.blocks
            val count = blocks.size
            if (committed.isNotEmpty()) {
                for (block in MessageMarkdown.blocks(committed)) blocks = blocks.append(PreparedReplyBlock.prepare(block))
            }
            val end = previous.prefixLength + committed.length
            previous = Document(end, blocks, streaming, count) { chunks.text.substring(0, end) }
        }
        return StreamPreparation(previous, buffer.tail())
    }

    fun prepare(source: String, streaming: Boolean): Document {
        if (lastChunks != null) { lastChunks = null; buffer = ReplyStreamBuffer() }
        val reusable = previous.stable && source.startsWith(previous.prefix)
        val start = if (reusable) previous.prefixLength else 0
        val end = if (streaming) MessageMarkdown.stablePrefixLength(source, start) else source.length
        if (reusable && end == start && (streaming || previous.stable)) return previous
        val prefix = source.substring(0, end)
        if (!streaming && prefix == previous.prefix) return previous
        val suffix = MessageMarkdown.blocks(source.substring(start, end)).map(PreparedReplyBlock::prepare)
        var blocks = if (reusable) previous.blocks else PersistentSequence()
        val reusedCount = blocks.size
        for (block in suffix) blocks = blocks.append(block)
        return Document(end, blocks, streaming, reusedCount) { prefix }.also { previous = it }
    }
}
