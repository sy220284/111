package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionFeedbackCopyTest {
    @Test fun writeActionsHaveHumanReadableResult() {
        assertEquals("插件已安装", ActionFeedbackCopy.success("plugin/install", JSONObject()).message)
        assertEquals("任务目标已保存", ActionFeedbackCopy.success("thread/goal/set", JSONObject()).message)
        assertEquals("权限及配置已保存", ActionFeedbackCopy.success("config/batchWrite", JSONObject()).message)
    }

    @Test fun toolErrorResultMustNotBeReportedAsSuccess() {
        val result = JSONObject().put("isError", true)
        val receipt = ActionFeedbackCopy.success("mcpServer/tool/call", result)
        assertTrue(receipt.isError)
        assertTrue(receipt.message.contains("错误"))
    }

    @Test fun failuresKeepContextWithoutInternalRequestNames() {
        val receipt = ActionFeedbackCopy.failure("plugin/install", "网络中断")
        assertTrue(receipt.isError)
        assertEquals("安装插件失败：网络中断", receipt.message)
        assertFalse(receipt.message.contains("plugin/install"))
        assertEquals("安装插件失败：连接拒绝",
            ActionFeedbackCopy.failure("plugin/install", "plugin/install：连接拒绝").message)
    }

    @Test fun unknownActionsHaveSafeHumanReadableFeedback() {
        assertEquals("操作已完成", ActionFeedbackCopy.success("new/method", JSONObject()).message)
        assertTrue(ActionFeedbackCopy.failure("new/method", "").message.contains("请检查"))
    }
}
