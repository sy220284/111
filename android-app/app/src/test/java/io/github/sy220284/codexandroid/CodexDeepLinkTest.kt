package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class CodexDeepLinkTest {
    @Test fun acceptsSingleSafeThreadIdentifier() {
        assertEquals("019a1b2c-aaaa-bbbb",
            CodexDeepLink.threadId("codex://thread/019a1b2c-aaaa-bbbb"))
        assertEquals("thread_20261122",
            CodexDeepLink.threadId("codex://thread/thread_20261122"))
    }

    @Test fun refusesUntrustedDestinationsAndParameters() {
        for (raw in listOf(
            "https://thread/anything", "codex://other/019a1b2c",
            "codex://thread/../../projects", "codex://thread/short",
            "codex://thread/019a1b2c?prompt=delete",
            "codex://thread/019a1b2c#secret",
            "codex://guest@thread/019a1b2c",
            "codex://thread/019a1b2c/extra"
        )) assertNull(raw, CodexDeepLink.threadId(raw))
    }
}
