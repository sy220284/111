package io.github.sy220284.codexandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstTurnRecoveryPolicyTest {
    @Test fun onlyExplicitServerRejectionQualifiesForEmptyThreadCleanup() {
        assertTrue(FirstTurnRecoveryPolicy.explicitlyRejected(
            RpcServerRejection("turn/start", -32602, "模型不可用")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalStateException("请求超时")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalStateException("turn/start：内核返回的结果为空或类型错误")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            RpcServerRejection("thread/start", -32602, "线程启动失败")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalStateException("thread/start：会话建立失败")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalArgumentException("turn/start：输入不正确")))
    }
}
