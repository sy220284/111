package io.github.sy220284.codexandroid

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ForegroundServiceReadinessTest {
    @Test fun dispatchDoesNotCountAsForegroundReadyUntilPromotionIsConfirmed() = runBlocking {
        val gate = ForegroundServiceReadiness()
        gate.begin()
        val waiter = async { gate.awaitReady(2_000L) }
        yield()
        assertFalse(waiter.isCompleted)
        gate.ready()
        waiter.await()
    }

    @Test fun failedPromotionReturnsConcreteErrorToCaller() = runBlocking {
        val gate = ForegroundServiceReadiness()
        gate.begin()
        gate.failed("系统拒绝启动")
        try {
            gate.awaitReady(100L)
            fail("A failed promotion must prevent command dispatch")
        } catch (expected: IllegalStateException) {
            assertEquals("系统拒绝启动", expected.message)
        }
        gate.begin()
        gate.ready()
        gate.awaitReady(100L)
    }

    @Test fun serviceNeverPromotedMustTimeOutInsteadOfLaunchingCommand() = runBlocking {
        val gate = ForegroundServiceReadiness()
        gate.begin()
        try {
            gate.awaitReady(40L)
            fail("Missing promotion must time out")
        } catch (expected: TimeoutCancellationException) {
            assertEquals(ForegroundStartState.Starting, gate.state.value)
        }
    }

    @Test fun destructionClearsReadinessAndFailsPendingStart() = runBlocking {
        val gate = ForegroundServiceReadiness()
        gate.begin()
        gate.ready()
        gate.stopped()
        assertEquals(ForegroundStartState.Stopped, gate.state.value)
        gate.begin()
        gate.failed("服务已停止")
        gate.stopped()
        assertEquals(ForegroundStartState.Failed("服务已停止"), gate.state.value)
    }
}
