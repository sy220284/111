package io.github.sy220284.codexandroid

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalMutationAdmissionTest {
    @Test fun activeMutationCountCoversSuspendingWorkAndCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = async {
            AppDataTransfer.withLocalMutation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            assertEquals(1, AppDataTransfer.localMutations.value)
            AppDataTransfer.withLocalMutation { assertEquals(2, AppDataTransfer.localMutations.value) }
            assertEquals(1, AppDataTransfer.localMutations.value)
        } finally {
            job.cancelAndJoin()
        }
        assertEquals(0, AppDataTransfer.localMutations.value)
    }

    @Test fun busyTransferRejectsMutationAndDeferredDraftSaveResumes() = runBlocking {
        val previous = AppDataTransfer.state.value
        AppDataTransfer.state.value = DataTransferState(busy = true)
        try {
            var invoked = false
            assertTrue(runCatching { AppDataTransfer.withLocalMutation { invoked = true } }.isFailure)
            assertFalse("备份时不得进入本机写入", invoked)
            val persisted = CompletableDeferred<Unit>()
            val pending = async { AppDataTransfer.persistDrafts { persisted.complete(Unit) } }
            kotlinx.coroutines.yield()
            assertFalse(persisted.isCompleted)
            AppDataTransfer.state.value = previous
            pending.await()
            assertTrue(persisted.isCompleted)
            assertEquals(0, AppDataTransfer.localMutations.value)
        } finally {
            AppDataTransfer.state.value = previous
        }
    }
}
