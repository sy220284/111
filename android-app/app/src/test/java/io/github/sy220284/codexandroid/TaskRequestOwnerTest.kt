package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class TaskRequestOwnerTest {
    @Test fun ownerCannotCrossConnectionOrAccount() {
        val owner = TaskRequestOwner(42L, 7L)
        assertTrue(owner.matches(42L, 7L))
        assertFalse(owner.matches(null, 7L))
        assertFalse(owner.matches(43L, 7L))
        assertFalse(owner.matches(42L, 8L))
    }

    @Test fun mediaSummaryDoesNotMislabelAudioAndVideo() {
        assertEquals("媒体附件：图片 1 份、音频 1 份、视频 1 份",
            LocalMediaAttachmentPolicy.summary(listOf("a.jpg", "b.m4a", "c.mp4")))
    }
}
