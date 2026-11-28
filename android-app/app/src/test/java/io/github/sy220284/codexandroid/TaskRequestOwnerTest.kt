package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
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

    @Test fun staleOwnerRejectsSendAndLateResponse() = runBlocking {
        val owner = TaskRequestOwner(42L, 7L)
        var account = 8L
        var sent = 0
        assertTrue(runCatching { owner.guarded({ owner.matches(42L, account) }) { ++sent } }.isFailure)
        assertEquals(0, sent)
        account = 7L
        val result = runCatching {
            owner.guarded({ owner.matches(42L, account) }) {
                sent++
                account = 8L
                "old response"
            }
        }
        assertTrue(result.isFailure)
        assertEquals(1, sent)
    }

    @Test fun cancellationIsNotConvertedToSuccess() = runBlocking {
        val owner = TaskRequestOwner(42L, 7L)
        val result = runCatching {
            owner.guarded({ true }) { throw CancellationException("cancelled") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun mediaSummaryDoesNotMislabelAudioAndVideo() {
        assertEquals("媒体附件：图片 1 份、音频 1 份、视频 1 份",
            LocalMediaAttachmentPolicy.summary(listOf("a.jpg", "b.m4a", "c.mp4")))
    }
}
