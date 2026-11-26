package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ReplyReadingNavigationTest {
    @Test fun nestedMarkupAndSelectionShareOneParse() {
        val source = "**粗体 *斜体*** ~~删除~~ [链接](https://example.com)"
        val parts = MessageMarkdown.copyParts(source)
        assertTrue(parts.any { it.visible == "斜体" && it.styles.containsAll(setOf("strong", "emphasis")) })
        assertTrue(parts.any { it.visible == "删除" && "strike" in it.styles })
        assertTrue(parts.any { it.visible == "链接" && it.url == "https://example.com" })
        assertEquals("~~删除~~" to CopyContentFormat.Markdown,
            MessageSelectionCopy.resolve(MessageMarkdown.blocks(source), "删除"))
    }
    @Test fun outlineMapsCompletedFragmentsAndAnchor() {
        val first = TimelineItem("p", "agentMessage", "## 第一章\n\n", "completed", turnId = "t", phase = "partial_answer")
        val last = TimelineItem("f", "agentMessage", "## 第二章", "completed", turnId = "t", phase = "final_answer")
        val rows = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(listOf(first, last)), false, null, false)
        assertEquals(listOf("第一章", "第二章"), ReplyReadingNavigation.outline(rows).map { it.title })
        assertEquals(0, ReplyReadingNavigation.replacementIndex(rows, "f"))
        assertEquals(0, ReplyReadingNavigation.replacementIndex(rows, "p"))
        assertNotNull(RichReplyContent.parse("codex-file", "报告|output/report.md"))
    }
}
