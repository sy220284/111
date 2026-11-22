package io.github.sy220284.codexandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstTurnRecoveryPolicyTest {
    @Test fun onlyExplicitServerRejectionQualifiesForEmptyThreadCleanup() {
        assertTrue(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalStateException("turn/start：模型不可用")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalStateException("请求超时")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalStateException("thread/start：会话建立失败")))
        assertFalse(FirstTurnRecoveryPolicy.explicitlyRejected(
            IllegalArgumentException("turn/start：输入不正确")))
    }
}
