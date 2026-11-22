package io.github.sy220284.codexandroid

import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnSubmissionCoordinatorTest {
    private fun created(id: String) = JSONObject().put("thread", JSONObject().put("id", id))
    private fun started(id: String) = JSONObject().put("turn", JSONObject().put("id", id))
    private fun createParams() = JSONObject().put("cwd", "/projects")
    private fun turnParams(id: String) = JSONObject().put("threadId", id)

    @Test fun newThreadAndTurnStartAcceptedInOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val createdIds = mutableListOf<String>()
        val result = TurnSubmissionCoordinator { method, params ->
            calls += method
            when (method) {
                "thread/start" -> created("thread-one")
                else -> {
                    assertEquals("thread-one", params.getString("threadId"))
                    started("turn-one")
                }
            }
        }.submit(null, ::createParams, ::turnParams, createdIds::add)
        assertEquals(listOf("thread/start", "turn/start"), calls)
        assertEquals(listOf("thread-one"), createdIds)
        assertEquals(TurnSubmissionOutcome.Accepted("thread-one", "turn-one", "thread-one"), result)
    }

    @Test fun existingThreadSkipsCreationAndKeepsItsOwnId() = runBlocking {
        val methods = mutableListOf<String>()
        val result = TurnSubmissionCoordinator { method, params ->
            methods += method
            assertEquals("already-open", params.getString("threadId"))
            started("turn-two")
        }.submit("already-open", ::createParams, ::turnParams) {
            fail("Existing thread must never be recreated")
        }
        assertEquals(listOf("turn/start"), methods)
        assertEquals(TurnSubmissionOutcome.Accepted("already-open", "turn-two", null), result)
    }

    @Test fun explicitRejectionAfterCreationAllowsVerifiedEmptyThreadRecovery() = runBlocking {
        val result = TurnSubmissionCoordinator { method, _ ->
            if (method == "thread/start") created("created")
            else throw RpcServerRejection("turn/start", -32602, "配置被拒绝")
        }.submit(null, ::createParams, ::turnParams) {}
        val failure = result as TurnSubmissionOutcome.Failed
        assertEquals("created", failure.newlyCreatedThreadId)
        assertTrue(failure.turnRequestDispatched)
        assertFalse(failure.requiresReconciliation)
        assertTrue(FirstTurnRecoveryPolicy.explicitlyRejected(failure.failure))
    }

    @Test fun firstRpcFailureNeverSendsTurnAndKeepsOriginalDraft() = runBlocking {
        val calls = mutableListOf<String>()
        val result = TurnSubmissionCoordinator { method, _ ->
            calls += method
            throw IllegalStateException("会话创建失败")
        }.submit(null, ::createParams, ::turnParams) {}
        val failure = result as TurnSubmissionOutcome.Failed
        assertEquals(listOf("thread/start"), calls)
        assertNull(failure.newlyCreatedThreadId)
        assertFalse(failure.turnRequestDispatched)
        assertFalse(failure.requiresReconciliation)
        assertTrue(SubmissionFailurePolicy.message(failure.failure, failure.turnRequestDispatched)
            .contains("未提交"))
    }

    @Test fun lostResponseMustRemainAmbiguousUntilServerReconciliation() = runBlocking {
        val result = TurnSubmissionCoordinator { method, _ ->
            if (method == "thread/start") created("maybe-running")
            else throw SocketTimeoutException("等待回合响应超时")
        }.submit(null, ::createParams, ::turnParams) {}
        val failure = result as TurnSubmissionOutcome.Failed
        assertEquals("maybe-running", failure.newlyCreatedThreadId)
        assertTrue(failure.turnRequestDispatched)
        assertTrue(failure.requiresReconciliation)
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(failure.failure))
        assertTrue(SubmissionFailurePolicy.message(failure.failure, failure.turnRequestDispatched)
            .contains("可能已开始执行"))
    }

    @Test fun missingTurnIdAfterAcknowledgementIsStillAmbiguous() = runBlocking {
        val result = TurnSubmissionCoordinator { method, _ ->
            if (method == "thread/start") created("thread-one") else JSONObject()
        }.submit(null, ::createParams, ::turnParams) {}
        val failure = result as TurnSubmissionOutcome.Failed
        assertTrue(failure.turnRequestDispatched)
        assertTrue(failure.requiresReconciliation)
        assertEquals("thread-one", failure.threadId)
    }

    @Test fun localTurnParameterValidationFailureDoesNotDispatch() = runBlocking {
        val requests = mutableListOf<String>()
        val result = TurnSubmissionCoordinator { method, _ ->
            requests += method
            started("never")
        }.submit("existing", ::createParams, { throw IllegalArgumentException("输入无效") }) {}
        val failure = result as TurnSubmissionOutcome.Failed
        assertTrue(requests.isEmpty())
        assertFalse(failure.turnRequestDispatched)
    }
}
