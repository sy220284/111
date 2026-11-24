package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ConversationCopyContentTest {
    @Test fun markdownKeepsExactSourceAndSafeRichHtml() {
        val source = "# 标题\n\n**粗体**\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n![图片](https://example.com/secret.png)\n\n[本地](file:///tmp/test)\n\n<script>alert(1)</script>"
        val payload = ConversationCopyContent.payload(source, CopyContentFormat.Markdown)
        assertEquals(source, payload.text)
        val html = requireNotNull(payload.html)
        assertTrue(html.contains("<h1>"))
        assertTrue(html.contains("<strong>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("图片"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("href=\"file:"))
    }

    @Test fun literalAndVisibleTextAreSeparateFromMarkdown() {
        val source = "**粗体**"
        assertEquals(CopyPayload(source), ConversationCopyContent.payload(source, CopyContentFormat.Literal))
        assertEquals("粗体", ConversationCopyContent.payload(source, CopyContentFormat.VisibleText).text.trim())
    }

    @Test fun tableRangeDoesNotIncludeUnselectedValues() {
        val rows = listOf(listOf("头一", "头二", "头三"), listOf("排除", "a|b", "c"), listOf("排除2", "d", "e"))
        val result = ConversationCopyContent.tableRange(rows, 1, 2, 1, 2)
        assertEquals("|  |  |\n| --- | --- |\n| a\\|b | c |\n| d | e |", result)
        assertFalse(result.contains("排除"))
        assertFalse(result.contains("头二"))
    }

    @Test fun visibleSelectionRestoresOnlySelectedInlineMarkup() {
        val blocks = MessageMarkdown.blocks("普通 **粗体内容** 与 [链接](https://example.com)\n\n下一段")
        val partial = MessageSelectionCopy.resolve(blocks, "体内")
        assertEquals("**体内**" to CopyContentFormat.Markdown, partial)
        assertEquals("[链接](https://example.com)" to CopyContentFormat.Markdown,
            MessageSelectionCopy.resolve(blocks, "链接"))
        assertEquals(null, MessageSelectionCopy.resolve(MessageMarkdown.blocks("**重复** 重复"), "重复"))
        assertEquals("普通" to CopyContentFormat.Literal, MessageSelectionCopy.resolve(blocks, "普通"))
    }
}
