package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class SubmissionFailurePolicyTest {
    @Test fun noTurnRequestWasSentSoRetryIsSafe() {
        val message = SubmissionFailurePolicy.message(IllegalArgumentException("配置不完整"), false)
        assertTrue(message.contains("未提交"))
        assertTrue(message.contains("可重试"))
    }

    @Test fun explicitCoreRejectionCanBeRetried() {
        val message = SubmissionFailurePolicy.message(
            RpcServerRejection("turn/start", -32602, "运行配置被拒绝"), true)
        assertTrue(message.contains("明确拒绝"))
        assertTrue(message.contains("重试"))
    }

    @Test fun timeoutMustNotEncourageBlindDuplicateExecution() {
        val message = SubmissionFailurePolicy.message(
            java.net.SocketTimeoutException("请求超时"), true)
        assertTrue(message.contains("可能已开始执行"))
        assertTrue(message.contains("核对会话记录"))
        assertFalse(message.contains("原文已保留，可重试"))
    }
}
