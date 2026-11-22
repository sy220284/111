package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class SessionRefreshPolicyTest {
    @Test fun refreshesAfterThreadMutationsButNotOnDrawerAndStaticReads() {
        for (method in listOf("thread/name/set", "thread/archive", "thread/delete",
            "thread/unarchive", "thread/fork", "thread/section/move")) {
            assertTrue(method, SessionRefreshPolicy.affectsThreads(method))
        }
        for (method in listOf("thread/list", "thread/read", "model/list", "config/read")) {
            assertFalse(method, SessionRefreshPolicy.affectsThreads(method))
        }
    }
}
