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
}
