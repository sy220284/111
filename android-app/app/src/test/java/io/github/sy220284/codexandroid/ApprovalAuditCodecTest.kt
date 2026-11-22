package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalAuditCodecTest {
    @Test fun roundTripPreservesDecisionCommandAndTime() {
        val original = listOf(
            ApprovalAuditRecord(1730000000000, "执行命令", "本会话允许", "git status"),
            ApprovalAuditRecord(1730000000001, "修改文件", "已拒绝", "不改动机密配置")
        )
        assertEquals(original, ApprovalAuditCodec.deserialize(
            ApprovalAuditCodec.serialize(original)))
    }

    @Test fun historyIsBoundedToTheMostRecentHundred() {
        val original = (1..125).reversed().map {
            ApprovalAuditRecord(it.toLong(), "执行命令", "允许本次", "command-$it")
        }
        val decoded = ApprovalAuditCodec.deserialize(
            ApprovalAuditCodec.serialize(original))
        assertEquals(100, decoded.size)
        assertEquals(125L, decoded.first().timeMs)
        assertEquals(26L, decoded.last().timeMs)
    }

    @Test fun capturesOnlyRelevantCommandDetails() {
        val prompt = ServerPrompt(8, "item/commandExecution/requestApproval",
            JSONObject().put("command", "ls -la").put("cwd", "/private/work"))
        val result = ApprovalAuditCodec.from(prompt, "accept", 1730000000000)
        assertEquals("ls -la", result.summary)
        assertEquals("允许本次", result.decision)
        assertFalse(ApprovalAuditCodec.serialize(listOf(result)).contains("/private/work"))
        assertTrue(ApprovalAuditCodec.describeDecision("acceptForSession").contains("会话"))
    }
}
