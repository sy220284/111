package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadMarkdownExporterTest {
    @Test fun exportsRealMessagesInChronologicalOrderWithoutInternalToolPayloads() {
        val items = JSONArray()
            .put(JSONObject().put("type", "userMessage")
                .put("content", JSONArray().put(JSONObject().put("text", "请检查代码"))))
            .put(JSONObject().put("type", "commandExecution")
                .put("command", "secret-command --token private"))
            .put(JSONObject().put("type", "agentMessage")
                .put("text", "已核对 **文件**。"))
        val history = JSONObject().put("thread", JSONObject().put(
            "turns", JSONArray().put(JSONObject().put("items", items))))
        val markdown = ThreadMarkdownExporter.render(history)
        assertTrue(markdown.startsWith("# Codex 会话记录\n\n## 用户\n\n请检查代码"))
        assertTrue(markdown.contains("## Codex\n\n已核对 **文件**。"))
        assertFalse(markdown.contains("secret-command"))
        assertEquals(1, markdown.split("## 用户").size - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyHistoryDoesNotExportMisleadingFile() {
        ThreadMarkdownExporter.render(JSONObject().put("thread", JSONObject()))
    }

    @Test fun progressiveExportJoinsNativeFragmentsWithoutCommentaryOrToolText() {
        fun message(phase: String, text: String) = JSONObject().put("type", "agentMessage")
            .put("phase", phase).put("text", text)
        val items = JSONArray().put(JSONObject().put("type", "userMessage").put("text", "任务"))
            .put(message("partial_answer", "第一部分\n\n"))
            .put(message("commentary", "正在检查"))
            .put(JSONObject().put("type", "commandExecution").put("text", "工具内容"))
            .put(message("final_answer", "第二部分"))
        val history = JSONObject().put("thread", JSONObject().put("turns", JSONArray().put(JSONObject().put("items", items))))
        val markdown = ThreadMarkdownExporter.render(history)
        assertTrue(markdown.contains("## Codex\n\n第一部分\n\n第二部分"))
        assertEquals(1, markdown.split("## Codex").size - 1)
        assertFalse(markdown.contains("正在检查"))
        assertFalse(markdown.contains("工具内容"))
    }

    @Test fun executionExportIncludesOptedInEvidenceButNotPrivateReasoning() {
        val items = JSONArray().put(JSONObject().put("type", "agentMessage").put("text", "回答"))
            .put(JSONObject().put("type", "reasoning").put("summary", JSONArray().put("公开摘要")).put("content", JSONArray().put("私有推理")))
            .put(JSONObject().put("type", "commandExecution").put("command", "pwd").put("aggregatedOutput", "/workspace").put("exitCode", 0))
        val history = JSONObject().put("thread", JSONObject().put("turns", JSONArray().put(JSONObject().put("items", items))))
        val normal = ThreadMarkdownExporter.render(history)
        val complete = ThreadMarkdownExporter.render(history, includeActivity = true)
        assertFalse(normal.contains("/workspace"))
        assertTrue(complete.contains("/workspace"))
        assertTrue(complete.contains("公开摘要"))
        assertFalse(complete.contains("私有推理"))
    }

    @Test fun executionExportKeepsToolTextWithoutEmbeddedMediaAndHiddenReviewPrompts() {
        val result = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "工具正文"))
            .put(JSONObject().put("type", "image").put("data", "PRIVATE_BASE64")))
        val items = JSONArray()
            .put(JSONObject().put("type", "userMessage").put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("url", "data:image/png;base64,PRIVATE_USER_IMAGE"))))
            .put(JSONObject().put("type", "enteredReviewMode").put("review", "审查"))
            .put(JSONObject().put("type", "userMessage").put("text", "隐藏审查指令"))
            .put(JSONObject().put("type", "mcpToolCall").put("result", result))
            .put(JSONObject().put("type", "exitedReviewMode").put("review", "完成"))
        val history = JSONObject().put("thread", JSONObject().put("turns", JSONArray().put(JSONObject().put("items", items))))
        val output = ThreadMarkdownExporter.render(history, includeActivity = true)
        assertTrue(output.contains("[图片附件]"))
        assertTrue(output.contains("工具正文"))
        assertTrue(output.contains("[返回图片]"))
        assertFalse(output.contains("PRIVATE_BASE64"))
        assertFalse(output.contains("PRIVATE_USER_IMAGE"))
        assertFalse(output.contains("隐藏审查指令"))
    }
}
