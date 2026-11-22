package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftPersistenceTest {
    @Test fun allUnsentTextsSurviveRoundTripBeyondLegacyLimit() {
        val drafts = (1..200).associate { "thread-$it" to ComposerDraft("未发送 $it") }
        val restored = DraftPersistence.decode(DraftPersistence.encode(drafts))
        assertEquals(200, restored.size)
        assertEquals("未发送 1", restored["thread-1"]?.text)
        assertEquals("未发送 200", restored["thread-200"]?.text)
    }

    @Test fun independentChineseThreadDraftsSurviveRoundTrip() {
        val source = mapOf(
            ThreadComposerDrafts.NEW_THREAD to ComposerDraft("新项目\n请核查配置"),
            "thread-1" to ComposerDraft("检查 PR #30", listOf("/cache/private.jpg")),
            "empty" to ComposerDraft()
        )
        val restored = DraftPersistence.decode(DraftPersistence.encode(source))
        assertEquals("新项目\n请核查配置", restored[ThreadComposerDrafts.NEW_THREAD]?.text)
        assertEquals("检查 PR #30", restored["thread-1"]?.text)
        assertTrue(restored["thread-1"]?.images.orEmpty().isEmpty())
        assertFalse(restored.containsKey("empty"))
    }
}
