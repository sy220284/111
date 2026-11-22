package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanExecutionContractTest {
    @Test fun requestIncludesThreadAndExactApprovedMarkdown() {
        val text = "第一步：查看项目\n第二步：修改代码"
        val json = PlanExecutionContract.prepare("thread-7", text)
        assertEquals("thread-7", json.getString("threadId"))
        assertEquals(text, json.getString("markdown"))
        assertFalse(json.has("attachmentPath"))
    }

    @Test fun responsePromptIsRequiredBeforeExecuting() {
        assertEquals("请执行方案", PlanExecutionContract.prompt(
            JSONObject().put("prompt", " 请执行方案 ")))
        try {
            PlanExecutionContract.prompt(JSONObject())
            fail("Missing prepared prompt must stop execution")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun invalidPlanCannotBeSubmitted() {
        try {
            PlanExecutionContract.prepare("thread-7", " ")
            fail("Empty plan must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
}
