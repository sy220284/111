package io.github.sy220284.codexandroid

/** Presentation pages are independent lazy-list items. Their first key is the
 * native row key; continuation keys never depend on text or completion status.
 * The authoritative native item and the original copy/action text stay intact. */
internal data class ReplyRenderPage(val blocks: List<PreparedReplyBlock>, val firstHeading: Int) {
    val headingCount: Int get() = blocks.count { it.block.kind == "heading" }
    fun ownsHeading(index: Int) = index >= firstHeading && index < firstHeading + headingCount
}

internal data class TimelineDisplayRow(val row: ProgressiveReplyProjection.Row,
    val page: ReplyRenderPage? = null, val pageIndex: Int = 0, val lastPage: Boolean = true) {
    val key: String get() = if (pageIndex == 0) row.key else continuationKey(row.key, pageIndex)
    companion object {
        private fun continuationKey(id: String, index: Int) = "reply-page:${id.length}:$id:$index"
    }
}

/** Only the background presentation worker owns these document engines.
 * Unchanged historical replies retain their prepared pages. */
internal class TimelineReplyPages {
    private data class Entry(val item: TimelineItem, val engine: ReplyDocumentEngine,
        val pages: List<ReplyRenderPage>)
    private var cache = emptyMap<String, Entry>()
    fun project(rows: List<ProgressiveReplyProjection.Row>): List<TimelineDisplayRow> {
        val next = mutableMapOf<String, Entry>()
        val result = buildList {
            for (row in rows) {
                val item = row.group.singleOrNull()?.takeIf {
                    !row.forceActivity && ProgressiveReplyProjection.answer(it) && it.text.isNotBlank()
                }
                if (item == null) { add(TimelineDisplayRow(row)); continue }
                val old = cache[row.key]
                val entry = if (old?.item === item) old else {
                    val engine = old?.engine ?: ReplyDocumentEngine()
                    val live = item.status in setOf("inProgress", "in_progress", "running", "streaming", "sending")
                    val source = item.text
                    val document = engine.prepare(source, live)
                    val prepared = document.blocks + textChunks(source.substring(document.prefix.length))
                        .map(PreparedReplyBlock::tail)
                    Entry(item, engine, paginate(prepared))
                }
                next[row.key] = entry
                if (entry.pages.isEmpty()) add(TimelineDisplayRow(row))
                else entry.pages.forEachIndexed { index, page ->
                    add(TimelineDisplayRow(row, page, index, index == entry.pages.lastIndex))
                }
            }
        }
        cache = next
        return result
    }

    companion object {
        const val BLOCKS_PER_PAGE = 8
        const val TEXT_CHUNK = 3200
        /** Do not split a Unicode surrogate pair at a render boundary. */
        fun textChunks(source: String): List<String> = buildList {
            var start = 0
            while (start < source.length) {
                var end = minOf(source.length, start + TEXT_CHUNK)
                if (end < source.length && Character.isHighSurrogate(source[end - 1]) &&
                    Character.isLowSurrogate(source[end])) end--
                add(source.substring(start, end))
                start = end
            }
        }

        fun paginate(blocks: List<PreparedReplyBlock>): List<ReplyRenderPage> {
            // A very long literal paragraph is also independently laid out in
            // bounded chunks. Rich code/table/math retain their native block semantics.
            val bounded = blocks.flatMap { prepared ->
                val block = prepared.block
                val parts = prepared.inline[block.text]
                if (block.kind == "paragraph" && block.text.length > TEXT_CHUNK &&
                    parts?.size == 1 && parts.single() == MessageMarkdown.CopyPart(block.text))
                    textChunks(block.text).map { part ->
                        PreparedReplyBlock(MessageBlock("paragraph", part),
                            mapOf(part to listOf(MessageMarkdown.CopyPart(part))))
                    }
                else listOf(prepared)
            }
            var heading = 0
            return bounded.chunked(BLOCKS_PER_PAGE).map { group ->
                ReplyRenderPage(group, heading).also { heading += it.headingCount }
            }
        }
    }
}
