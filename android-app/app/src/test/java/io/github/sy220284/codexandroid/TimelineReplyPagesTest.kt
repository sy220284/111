package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class TimelineReplyPagesTest {
    @Test fun completedBlocksHaveStableLazyKeysAndOneActionOwner() {
        val engine = TimelineReplyPages()
        val prefix = (1..36).joinToString("") { "## 标题 $it\n\n正文 $it\n\n" }
        fun project(text: String, status: String): List<TimelineDisplayRow> {
            val item = TimelineItem("answer", "agentMessage", text, status, turnId = "turn", phase = "final_answer")
            return engine.project(listOf(ProgressiveReplyProjection.Row("answer", listOf(item))))
        }
        val live = project(prefix + "尾部", "streaming")
        val final = project(prefix + "尾部完成", "completed")
        assertTrue(live.size > 1)
        assertEquals("answer", live.first().key)
        assertEquals(live.map { it.key }, final.map { it.key })
        assertEquals(1, final.count { it.lastPage })
        assertEquals(36, final.sumOf { it.page?.headingCount ?: 0 })
        assertEquals(1, final.count { it.page?.ownsHeading(25) == true })
        assertSame(live.first().page!!.blocks.first(), final.first().page!!.blocks.first())
        for (index in 0 until live.lastIndex) assertSame(live[index].page, final[index].page)
        val replaced = project("## 新标题\n\n替换正文", "completed")
        assertEquals(1, replaced.size)
        assertNotSame(final.first().page, replaced.first().page)
        assertEquals(1, replaced.first().page!!.headingCount)
        assertEquals(prefix + "尾部完成", final.last().row.group.single().text)
    }

    @Test fun logicalIndexHandlesThousandsOfPagesWithoutPerPageMap() {
        val row = ProgressiveReplyProjection.Row("answer:含冒号", listOf(TimelineItem("answer", "agentMessage", "text")))
        val pages = List(10000) { i -> ReplyRenderPage(listOf(PreparedReplyBlock.tail("page-$i")), 0) }
        val list = TimelineDisplayRows(listOf(TimelineDisplayRows.Span(row, pages),
            TimelineDisplayRows.Span(row.copy(key = "next"), emptyList())))
        assertEquals(10001, list.size)
        for (i in list.indices) assertEquals(i, list.position(list[i].key))
        assertEquals(10000, list.position("next"))
        assertNull(list.position("reply-page:2:no:999"))
        assertTrue(list[9999].lastPage)
        assertFalse(list[9998].lastPage)
    }

    @Test fun codeSlicesPreserveSourceAndTokenCoverage() {
        val source = (0 until 150).joinToString("\n") { "val x$it = \"字😀\"" } + "\n" + "😀".repeat(4000)
        val tokens = ReplyCodeHighlight.prepare(source, "kt")
        val slices = ReplyCodeSlices.prepare(source, tokens)
        assertTrue(slices.size > 1)
        assertEquals(source, slices.joinToString("") { it.text })
        assertTrue(slices.all { it.text.length <= 3200 })
        assertTrue(slices.none { Character.isHighSurrogate(it.text.last()) || Character.isLowSurrogate(it.text.first()) })
        for (slice in slices) for (token in slice.tokens) assertTrue(token.end <= slice.text.length)
        assertEquals(tokens.sumOf { it.end - it.start }, slices.sumOf { s -> s.tokens.sumOf { it.end - it.start } })
    }

    @Test fun rawTailChunksPreserveTextAndUnicodePairs() {
        val text = "a".repeat(TimelineReplyPages.TEXT_CHUNK - 1) + "😀" + "字".repeat(10000)
        val chunks = TimelineReplyPages.textChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= TimelineReplyPages.TEXT_CHUNK })
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }

    @Test fun longLiteralParagraphUsesSeveralLazyPages() {
        val text = "字".repeat(TimelineReplyPages.TEXT_CHUNK * 20)
        val pages = TimelineReplyPages.paginate(listOf(PreparedReplyBlock.prepare(MessageBlock("paragraph", text))))
        assertTrue(pages.size >= 3)
        assertEquals(text, pages.flatMap { it.blocks }.joinToString("") { it.block.text })
    }
}
