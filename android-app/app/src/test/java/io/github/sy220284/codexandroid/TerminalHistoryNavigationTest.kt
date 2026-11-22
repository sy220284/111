package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalHistoryNavigationTest {
    private val recent = listOf("pwd", "ls -la", "git status")

    @Test fun upAndDownRestoreTheUnsentDraft() {
        val first = TerminalHistoryNavigation.previous("echo unfinished", recent,
            TerminalHistoryPosition())!!
        assertEquals("pwd", first.command)
        val second = TerminalHistoryNavigation.previous(first.command, recent, first.position)!!
        assertEquals("ls -la", second.command)
        val backward = TerminalHistoryNavigation.next(recent, second.position)!!
        assertEquals("pwd", backward.command)
        val draft = TerminalHistoryNavigation.next(recent, backward.position)!!
        assertEquals("echo unfinished", draft.command)
        assertEquals(TerminalHistoryPosition(), draft.position)
    }

    @Test fun oldestEntryStaysSelectedAndEmptyHistoryIsIgnored() {
        val old = TerminalHistoryNavigation.previous("draft", recent,
            TerminalHistoryPosition(2, "draft"))!!
        assertEquals("git status", old.command)
        assertEquals(2, old.position.index)
        assertNull(TerminalHistoryNavigation.previous("draft", emptyList(),
            TerminalHistoryPosition()))
        assertNull(TerminalHistoryNavigation.next(recent, TerminalHistoryPosition()))
    }

    @Test fun navigationPreservesDraftAcrossRepeatedUpKeys() {
        val first = TerminalHistoryNavigation.previous("custom unsent input", recent,
            TerminalHistoryPosition())!!
        val second = TerminalHistoryNavigation.previous(first.command, recent,
            first.position)!!
        val third = TerminalHistoryNavigation.previous(second.command, recent,
            second.position)!!
        assertEquals("custom unsent input", third.position.draft)
        assertEquals("git status", third.command)
    }
}
