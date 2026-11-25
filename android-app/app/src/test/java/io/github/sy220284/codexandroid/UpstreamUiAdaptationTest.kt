package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpstreamUiAdaptationTest {
    @Test fun mcpReceiptsAreScopedAndRetryReplacesOnlyItsOwnAttempt() {
        val first = McpStartupReceipt.parse(4, JSONObject("""{"name":"server","threadId":"t","environmentId":"a","status":"ready","durationMs":82}"""))!!
        val second = McpStartupReceipt.parse(4, JSONObject("""{"name":"server","threadId":"t","environmentId":"b","status":"failed","error":"unavailable"}"""))!!
        assertEquals(82L, first.durationMs)
        assertNull(second.durationMs)
        val retry = first.copy(status = "starting", durationMs = null)
        val merged = McpStartupReceipt.merge(listOf(first, second), retry)
        assertEquals(listOf(second, retry), merged)
        assertEquals(listOf(retry.copy(epoch = 5)), McpStartupReceipt.merge(merged, retry.copy(epoch = 5)))
    }

    @Test fun blockedHookUsesActualNativeOutcomeAndDoesNotExposeContext() {
        val result = HookRunPresentation.project(JSONObject("""{"turnId":"turn","run":{"id":"one","eventName":"preToolUse","status":"blocked","durationMs":10,"entries":[{"kind":"error","text":"policy failed"},{"kind":"context","text":"private context"}]}}"""))!!
        assertEquals("blocked", result.status)
        assertTrue(result.text.contains("policy failed"))
        assertFalse(result.text.contains("private context"))
        assertEquals("turn", result.turnId)
    }

    @Test fun hookPolicyPreservesSiblingHandlersAndGuardsUnsupportedBlocking() {
        val config = JSONObject("""{"PreToolUse":[{"hooks":[{"type":"command","command":"one"},{"type":"command","command":"two","async":true}]}]}""")
        val rows = HookPolicyCatalog.rows(config, "v1", false)
        assertEquals(2, rows.size)
        val changed = rows[0].change("block")
        assertEquals("block", changed.getJSONObject(0).getJSONArray("hooks").getJSONObject(0).getString("onFailure"))
        assertEquals("two", changed.getJSONObject(0).getJSONArray("hooks").getJSONObject(1).getString("command"))
        assertFalse(rows[0].handler.has("onFailure"))
        assertFalse(rows[1].canBlock)
        assertThrows(IllegalArgumentException::class.java) { rows[1].change("block") }
    }

    @Test fun runtimeStatusReflectsActualRetirementAndRecovery() {
        val failed = EngineStatus.Unavailable("lost")
        assertEquals("正在回收旧内核", RuntimeStatusPresentation.label(failed, true, false, 2000))
        assertEquals("等待自动恢复", RuntimeStatusPresentation.label(failed, false, false, 2000))
        assertEquals("正在初始化内核", RuntimeStatusPresentation.label(EngineStatus.Starting, false, false, null))
    }
}
