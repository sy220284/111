package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ReplyDocumentEngineTest {
    @Test fun stableBlocksReuseTokensAndFinalSourceMatchesOnePass() {
        val engine = ReplyDocumentEngine()
        val first = engine.prepare("**first**\n\nnext", true)
        assertEquals("**first**\n\n", first.prefix)
        val second = engine.prepare("**first**\n\nnext\n\nlast", true)
        assertSame(first.blocks.first(), second.blocks.first())
        assertEquals("first", second.blocks.first().inline.getValue("**first**").single().visible)
        val text = "**first**\n\nnext\n\nlast"
        val final = engine.prepare(text, false)
        assertEquals(MessageMarkdown.blocks(text), final.blocks.map { it.block })
        assertSame(first.blocks.first(), final.blocks.first())
        assertEquals(text, final.prefix)
    }

    @Test fun nativeChunkPreparationMatchesOnePassAcrossEveryBoundary() {
        val text = "## heading\n\n**bold**\n\n```kt\nval x = 1\n\n```\n\n" +
            "\$\$\n\\frac{1}{2}\n\n\$\$\n\n" + "字😀".repeat(5000)
        val engine = ReplyDocumentEngine()
        var chunks = TimelineTextChunks.from("")
        var consumed = 0
        for (part in text.chunked(137)) {
            consumed += part.length
            chunks = chunks.append(part)
            val result = engine.prepareChunks(chunks, true)
            val prefix = text.substring(0, consumed)
            assertEquals(MessageMarkdown.stablePrefixLength(prefix), result.document.prefixLength)
            assertEquals(prefix.substring(result.document.prefixLength), result.tail.joinToString("") { it.block.text })
        }
        val final = engine.prepareChunks(chunks, false)
        assertEquals(MessageMarkdown.blocks(text), final.document.blocks.map { it.block })
        assertTrue(final.tail.isEmpty())
        val replacement = engine.prepareChunks(TimelineTextChunks.from("replacement\n\n"), true)
        assertEquals("replacement\n\n", replacement.document.prefix)
        assertEquals("replacement", replacement.document.blocks.single().block.text)
    }

    @Test fun incompleteFenceAndFormulaAreNotCommittedAndReplacementInvalidatesPrefix() {
        val engine = ReplyDocumentEngine()
        val live = engine.prepare("intro\n\n```codex-math\n\\frac{1}{2}\n", true)
        assertEquals("intro\n\n", live.prefix)
        val finished = engine.prepare("intro\n\n```codex-math\n\\frac{1}{2}\n```", false)
        assertEquals("math", finished.blocks.last().block.kind)
        assertNotNull(finished.blocks.last().math["\\frac{1}{2}"])
        val changed = engine.prepare("replaced\n\nnew", true)
        assertEquals("replaced\n\n", changed.prefix)
        assertEquals("replaced", changed.blocks.single().block.text)
    }
}
