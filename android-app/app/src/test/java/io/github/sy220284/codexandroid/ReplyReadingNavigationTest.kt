package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ReplyReadingNavigationTest {
    @Test fun fileLinksRetainDestinationAndCopyAcrossMarkdownContainers() {
        for (path in listOf("./report.pdf", "/workspace/report.pdf", "sandbox:/workspace/report.pdf", "file:///workspace/report.pdf")) {
            val parts = MessageMarkdown.copyParts("[报告]($path)")
            assertEquals(path, parts.single().url)
            assertEquals("[报告]($path)" to CopyContentFormat.Markdown,
                MessageSelectionCopy.resolve(MessageMarkdown.blocks("[报告]($path)"), "报告"))
        }
        assertEquals("reports/a b.pdf", ReplyLinkPolicy.file("./reports/a%20b.pdf"))
        assertEquals("/workspace/a b.pdf", ReplyLinkPolicy.file("file:///workspace/a%20b.pdf"))
        assertEquals("C:/workspace/a b.pdf", ReplyLinkPolicy.file("C:\\workspace\\a%20b.pdf"))
        assertEquals("C:/workspace/a b.pdf", ReplyLinkPolicy.file("file:///C:/workspace/a%20b.pdf"))
        assertEquals("[报告](./reports/a%20b.pdf)" to CopyContentFormat.Markdown,
            MessageSelectionCopy.resolve(MessageMarkdown.blocks("[报告](<./reports/a b.pdf>)"), "报告"))
        for (path in listOf("../secret", "%2e%2e/secret", "javascript:alert(1)", "content://provider/x", "file://host/etc/passwd", "//host/x", "/x%00y")) {
            assertNull(path, ReplyLinkPolicy.file(path))
        }
    }
    @Test fun fileLinksKeepReservedCharactersWithoutOpeningAnotherFile() {
        assertEquals("reports/报告#2.pdf", ReplyLinkPolicy.file("./reports/报告#2.pdf"))
        assertEquals("reports/a?b+100%.txt", ReplyLinkPolicy.file("reports/a?b+100%.txt"))
        assertEquals("/workspace/a#2.pdf", ReplyLinkPolicy.file("file:///workspace/a%232.pdf"))
        assertEquals("reports/a%20.txt", ReplyLinkPolicy.file("reports/a%2520.txt"))
        assertNull(ReplyLinkPolicy.file("file:///workspace/a#2.pdf"))
        assertNull(ReplyLinkPolicy.file("file:///workspace/a?b"))
        assertNull(ReplyLinkPolicy.file("reports/%2e%2e/secret"))
        assertNull(ReplyLinkPolicy.file("reports/%FF.txt"))
        assertNull(ReplyLinkPolicy.file("file:///workspace/%FF.txt"))
        assertEquals("报告.txt", ReplyLinkPolicy.file("%E6%8A%A5%E5%91%8A.txt"))
        assertEquals("a%+1.txt", ReplyLinkPolicy.file("a%+1.txt"))
        assertEquals("reports/a%20.txt", ReplyLinkPolicy.fileCard("reports/a%20.txt"))
        assertEquals("reports/a b.txt", ReplyLinkPolicy.file("reports/a%20b.txt"))
        assertEquals("/workspace/a b.txt", ReplyLinkPolicy.fileCard("file:///workspace/a%20b.txt"))
        assertNull(ReplyLinkPolicy.fileCard("../secret"))
    }
    @Test fun completionKeepsEachFragmentsKeyAndTextOrigin() {
        val first = TimelineItem("p", "agentMessage", "第一章\n".repeat(100), "completed", turnId = "t", phase = "partial_answer")
        val last = TimelineItem("f", "agentMessage", "正在阅读第二章", "streaming", turnId = "t", phase = "final_answer")
        val live = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(listOf(first, last)), true, "t", false)
        val ended = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(listOf(first, last.copy(status = "completed"))), false, null, false)
        assertEquals(live.map { it.key }, ended.map { it.key })
        assertEquals(live.map { it.group.single().text }, ended.map { it.group.single().text })
        assertEquals(first.text + last.text, ended.last().actionItem?.text)
        assertEquals(1, ended.count { it.showActions })
    }
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
        assertEquals(1, ReplyReadingNavigation.replacementIndex(rows, "f"))
        assertEquals(0, ReplyReadingNavigation.replacementIndex(rows, "p"))
        assertNotNull(RichReplyContent.parse("codex-file", "报告|output/report.md"))
    }
}
