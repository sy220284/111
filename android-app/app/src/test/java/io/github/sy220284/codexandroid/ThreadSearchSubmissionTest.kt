package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ThreadSearchSubmissionTest {
    @Test fun onlySubmittedQueryCanDisplaySearchResults() {
        assertFalse(ThreadSearchSubmission.matches("新的搜索", "旧的搜索"))
        assertTrue(ThreadSearchSubmission.matches("  已提交  ", "已提交"))
        assertFalse(ThreadSearchSubmission.matches(" ", ""))
        assertFalse(ThreadSearchSubmission.matches("新词", ""))
    }

    @Test fun searchNeedsRealInputAndCannotRepeatWhileLoading() {
        assertFalse(ThreadSearchSubmission.canSubmit("   ", false))
        assertFalse(ThreadSearchSubmission.canSubmit("关键词", true))
        assertTrue(ThreadSearchSubmission.canSubmit(" 关键词 ", false))
        assertEquals("关键词", ThreadSearchSubmission.normalize(" 关键词 "))
    }
}
