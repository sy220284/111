package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class AdvancedReplyRenderTest {
    @Test fun complexTexAndNativeGraph() {
        val matrix = "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}"
        assertEquals("(a, b; c, d)", ReplyMath.parse(matrix)?.let(ReplyMath::linear))
        assertNotNull(ReplyMath.parse("\\left(\\frac{1}{\\sqrt[3]{x}}\\right)^2"))
        assertNotNull(ReplyMath.parse("\\begin{cases}x&x>0\\\\-x&x<0\\end{cases}"))
        assertNull(ReplyMath.parse("\\begin{pmatrix}x&y\\end{cases}"))
        assertNull(ReplyMath.parse("\\input{file}"))
        val graph = RichReplyContent.parse("mermaid", "flowchart TD\nA[开始] --> B{检查}\nB -->|通过| C[结束]\nB -->|失败| A")!!
        val layout = ReplyGraph.layout(graph.rows)
        assertEquals(3, layout.nodes.size)
        assertEquals(3, layout.edges.size)
        assertNull(RichReplyContent.parse("mermaid", "flowchart TD\nclick A \"https://example.com\""))
    }
}
