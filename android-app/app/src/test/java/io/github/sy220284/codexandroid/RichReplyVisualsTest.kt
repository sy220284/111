package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RichReplyVisualsTest {
    @Test fun validatedChartsAndGraphs() {
        assertEquals("bar", RichReplyContent.parse("codex-bar", "甲|-2\n乙|3")?.kind)
        assertEquals("line", RichReplyContent.parse("codex-line", "甲|0\n乙|3")?.kind)
        assertEquals("pie", RichReplyContent.parse("codex-pie", "甲|0\n乙|3")?.kind)
        for (bad in listOf("甲|NaN", "甲|Infinity", "甲|1\n甲|2", "甲|1e100"))
            assertNull(RichReplyContent.parse("codex-bar", bad))
        assertNull(RichReplyContent.parse("codex-pie", "甲|-1\n乙|2"))
        assertNull(RichReplyContent.parse("codex-pie", "甲|0"))
        assertNull(RichReplyContent.parse("codex-line", "甲|1"))
        assertNotNull(RichReplyContent.parse("codex-graph", "开始|成功|结束\n开始|失败|检查"))
        assertNull(RichReplyContent.parse("codex-graph", "开始|循环|开始"))
        assertNotNull(RichReplyContent.parse("codex-checklist", "done|已完成\ntodo|待完成"))
    }
    @Test fun mathIsBoundedAndUnsupportedCommandsStayLiteral() {
        val expression = "\\frac{a^2}{\\sqrt{b}}"
        assertNotNull(ReplyMath.parse(expression))
        assertEquals("(a^(2))/(√(b))", ReplyMath.parse(expression)?.let(ReplyMath::linear))
        assertNull(ReplyMath.parse("\\unknown{x}"))
        assertNull(ReplyMath.parse("{".repeat(20) + "a" + "}".repeat(20)))
        assertNull(RichReplyContent.parse("codex-math", "\\unknown{x}"))
    }
}
