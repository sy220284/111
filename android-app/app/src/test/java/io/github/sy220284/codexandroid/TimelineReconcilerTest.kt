package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineReconcilerTest {
    @Test fun identicalUserMessagesUsePendingMarkerOnly() {
        val items = listOf(
            TimelineItem("server-first", "user", "重复的指令"),
            TimelineItem("pending-uuid-2", "user", "重复的指令", "sending"),
            TimelineItem("assistant", "agent", "处理中")
        )
        assertEquals(1, TimelineReconciler.position(items, "server-second", "userMessage"))
        assertEquals(0, TimelineReconciler.position(items, "server-first", "userMessage"))
        assertEquals(-1, TimelineReconciler.position(items, "unseen-agent", "agentMessage"))
    }

    @Test fun olderAcceptedPendingIsNotMatchedAgain() {
        val items = listOf(
            TimelineItem("pending-old", "user", "任务", ""),
            TimelineItem("pending-new", "user", "任务", "sending")
        )
        assertEquals(1, TimelineReconciler.position(items, "actual-new", "userMessage"))
    }

    @org.junit.Test fun canonicalSummaryAndLifecycleAreRestoredFromNativeArrays() {
        val item = org.json.JSONObject().put("id", "reason").put("type", "reasoning")
            .put("summary", org.json.JSONArray().put("检查结构").put("完成核对"))
            .put("content", org.json.JSONArray().put("private"))
        val params = org.json.JSONObject().put("turnId", "turn").put("item", item)
        val complete = TimelineEventReducer.reduce("item/completed", params, emptyList()).single()
        org.junit.Assert.assertEquals("检查结构\n\n完成核对", complete.text)
        org.junit.Assert.assertEquals("completed", complete.status)
        org.junit.Assert.assertFalse(complete.evidence.contains("private"))
        val saved = org.json.JSONObject().put("turnId", "turn").put("item", item).put("completedAtMs", 1)
        org.junit.Assert.assertEquals(complete, TimelineEventReducer.historyEntry(saved))
    }

    @org.junit.Test fun commandOutputIsSeparateFromUserFacingActivityAndCompletionIsAuthoritative() {
        val item = org.json.JSONObject().put("id", "cmd").put("type", "commandExecution")
            .put("command", "pwd").put("status", "inProgress")
        val params = org.json.JSONObject().put("turnId", "turn").put("item", item)
        val started = TimelineEventReducer.reduce("item/started", params, emptyList())
        val delta = org.json.JSONObject().put("turnId", "turn").put("itemId", "cmd").put("delta", "/work\n")
        val streamed = TimelineEventReducer.reduce("item/commandExecution/outputDelta", delta, started)
        org.junit.Assert.assertEquals("运行命令", streamed.single().text)
        org.junit.Assert.assertEquals("/work\n", org.json.JSONObject(streamed.single().evidence).getString("aggregatedOutput"))
        item.put("status", "failed").put("exitCode", 1).put("aggregatedOutput", "/work\n")
        val ended = TimelineEventReducer.reduce("item/completed", params, streamed)
        org.junit.Assert.assertEquals("failed", ended.single().status)
        org.junit.Assert.assertEquals(ended, TimelineEventReducer.reduce("item/commandExecution/outputDelta", delta, ended))
    }

    @org.junit.Test fun patchCountAndActivityGroupingFollowTheTurnBoundaries() {
        val changes = org.json.JSONArray().put(org.json.JSONObject().put("path", "/work/a.kt"))
            .put(org.json.JSONObject().put("path", "/work/b.kt"))
        val raw = org.json.JSONObject().put("id", "patch").put("type", "fileChange").put("changes", changes)
        val patch = TimelineEventReducer.project(raw, "turn-a", "completed")
        org.junit.Assert.assertTrue(patch.text.contains("2 个文件"))
        val cmd = TimelineItem("cmd", "commandExecution", "运行命令", "completed", turnId = "turn-a")
        val another = cmd.copy(id = "cmd2", turnId = "turn-b")
        val answer = TimelineItem("answer", "agentMessage", "结果", turnId = "turn-a")
        org.junit.Assert.assertEquals(listOf(2, 1, 1),
            TimelineEventReducer.groups(listOf(patch, cmd, answer, another)).map { it.size })
    }
}
