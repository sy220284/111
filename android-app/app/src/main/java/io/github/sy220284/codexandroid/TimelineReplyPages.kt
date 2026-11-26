package io.github.sy220284.codexandroid

/** Presentation pages are independent lazy-list items. Their first key is the
 * native row key; continuation keys never depend on text or completion status.
 * The authoritative native item and the original copy/action text stay intact. */
internal data class ReplyRenderPage(val blocks: List<PreparedReplyBlock>, val firstHeading: Int) {
    val headingCount: Int = blocks.count { it.block.kind == "heading" }
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
        val pagination: PageEngine, val pages: List<ReplyRenderPage>)

    /** Complete pages share their original objects; only the unfinished page and
     * new committed blocks are processed on the next document revision. */
    private class PageEngine {
        private var previous: ReplyDocumentEngine.Document? = null
        private var complete = PersistentSequence<ReplyRenderPage>()
        private var pending = mutableListOf<PreparedReplyBlock>()
        private var heading = 0

        fun prepare(document: ReplyDocumentEngine.Document, tail: String): List<ReplyRenderPage> {
            if (previous !== document) {
                val old = previous
                val reuse = old != null && document.reusedBlockCount == old.blocks.size
                if (!reuse) {
                    complete = PersistentSequence()
                    pending = mutableListOf()
                    heading = 0
                }
                val start = if (reuse) document.reusedBlockCount else 0
                for (index in start until document.blocks.size) {
                    for (block in bounded(document.blocks[index])) {
                        pending.add(block)
                        if (pending.size == BLOCKS_PER_PAGE) {
                            val page = ReplyRenderPage(pending.toList(), heading)
                            complete = complete.append(page)
                            heading += page.headingCount
                            pending.clear()
                        }
                    }
                }
                previous = document
            }
            var nextHeading = heading
            val tailPages = (pending + textChunks(tail).map(PreparedReplyBlock::tail))
                .chunked(BLOCKS_PER_PAGE).map { group ->
                    ReplyRenderPage(group, nextHeading).also { nextHeading += it.headingCount }
                }
            return TimelineJoinedList(complete, tailPages)
        }
    }
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
                    val pagination = old?.pagination ?: PageEngine()
                    Entry(item, engine, pagination, pagination.prepare(document, source.substring(document.prefix.length)))
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
        fun textChunks(source: String): List<String> =
            if (source.isEmpty()) emptyList() else ReaderTextIndex.chunks(source, TEXT_CHUNK).map { it.text }

        private fun bounded(prepared: PreparedReplyBlock): List<PreparedReplyBlock> {
            val block = prepared.block
            val parts = prepared.inline[block.text]
            return if (block.kind == "paragraph" && block.text.length > TEXT_CHUNK &&
                parts?.size == 1 && parts.single() == MessageMarkdown.CopyPart(block.text))
                textChunks(block.text).map { part ->
                    PreparedReplyBlock(MessageBlock("paragraph", part),
                        mapOf(part to listOf(MessageMarkdown.CopyPart(part))))
                }
            else listOf(prepared)
        }

        fun paginate(blocks: List<PreparedReplyBlock>): List<ReplyRenderPage> {
            var heading = 0
            return blocks.flatMap(::bounded).chunked(BLOCKS_PER_PAGE).map { group ->
                ReplyRenderPage(group, heading).also { heading += it.headingCount }
            }
        }
    }
}
