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
        assertEquals(1, TimelineReconciler.position(items, "server-second", "userMessage", "uuid-2"))
        assertEquals(0, TimelineReconciler.position(items, "server-first", "userMessage"))
        assertEquals(-1, TimelineReconciler.position(items, "queued", "userMessage"))
        assertEquals(-1, TimelineReconciler.position(items, "queued", "userMessage", "other"))
        assertEquals(-1, TimelineReconciler.position(items, "unseen-agent", "agentMessage"))
    }

    @Test fun olderAcceptedPendingIsNotMatchedAgain() {
        val items = listOf(
            TimelineItem("pending-old", "user", "任务", ""),
            TimelineItem("pending-new", "user", "任务", "sending")
        )
        assertEquals(1, TimelineReconciler.position(items, "actual-new", "userMessage", "new"))
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
        var many = streamed
        repeat(128) {
            many = TimelineEventReducer.reduce("item/commandExecution/outputDelta",
                org.json.JSONObject(delta.toString()).put("delta", "0123456789"), many)
        }
        assertEquals("6789", many.single().commandOutputTail(4))
        assertEquals("/work\n" + "0123456789".repeat(128),
            org.json.JSONObject(many.single().evidence).getString("aggregatedOutput"))
        assertEquals("/work\n", streamed.single().commandOutputTail(20))
        val first = TimelineTextChunks.from("start")
        val next = first.append("middle").append("end")
        assertEquals("start", first.text)
        assertEquals("startmiddleend", next.text)
        assertEquals("leend", next.tail(5))
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
    @org.junit.Test
    fun liveSnapshotReconcilesWithoutRepeatingPersistedDeltas() {
        val saved = TimelineItem("answer", "agentMessage", "hello", "inProgress", turnId = "turn")
        val live = saved.copy(text = "hello world", status = "streaming")
        org.junit.Assert.assertEquals(listOf(live), ConversationLiveTimeline.merge(listOf(saved), listOf(live)))
        val completed = live.copy(status = "completed")
        org.junit.Assert.assertEquals(listOf(completed), ConversationLiveTimeline.merge(listOf(completed), listOf(live)))
        val indexed = IndexedTimeline.from((0..70).map { TimelineItem("item-$it", "agentMessage", "$it") })
        val edited = indexed.put(indexed[35].copy(text = "changed"))
        assertEquals("35", indexed[35].text)
        assertEquals("changed", edited.byId("item-35")?.text)
        assertEquals(indexed[70], edited[70])
        org.junit.Assert.assertSame(indexed.grouping.groups[0], edited.grouping.groups[0])
        org.junit.Assert.assertSame(indexed.grouping.groups[70], edited.grouping.groups[70])
        assertEquals("changed", edited.grouping.groups[35].single().text)
        val pending = indexed.put(TimelineItem("pending-c", "user", "draft"))
        val accepted = pending.put(TimelineItem("native", "user", "draft"), "c")
        assertEquals(pending.size, accepted.size)
        assertEquals("native", accepted.last().id)
        fun delta(id: String, value: String) = org.json.JSONObject().put("method", "item/agentMessage/delta")
            .put("params", org.json.JSONObject().put("threadId", "thread").put("turnId", "turn")
                .put("itemId", id).put("delta", value))
        val first = delta("answer", "a")
        val second = delta("answer", "b")
        val batch = TimelineDeltaBatch(first)
        org.junit.Assert.assertTrue(batch.append(second))
        org.junit.Assert.assertFalse(batch.append(delta("other", "c")))
        val sequential = TimelineEventReducer.reduce("item/agentMessage/delta", second.getJSONObject("params"),
            TimelineEventReducer.reduce("item/agentMessage/delta", first.getJSONObject("params"), emptyList()))
        assertEquals(sequential, TimelineEventReducer.reduce("item/agentMessage/delta",
            batch.notification().getJSONObject("params"), emptyList()))
    }

    @Test fun progressiveReplyKeepsLiveOrderingAndOneCompleteAnswerAfterTurn() {
        val user = TimelineItem("u", "user", "任务", "completed", turnId = "t")
        val partial = TimelineItem("p", "agentMessage", "第一部分\n\n", "completed", turnId = "t", phase = "partial_answer")
        val commentary = TimelineItem("c", "agentMessage", "继续检查", "completed", turnId = "t", phase = "commentary")
        val tool = TimelineItem("tool", "commandExecution", "运行命令", "completed", turnId = "t")
        val final = TimelineItem("f", "agentMessage", "第二部分", "streaming", turnId = "t", phase = "final_answer")
        val live = listOf(user, partial, commentary, tool, final)
        fun rows(items: List<TimelineItem>, running: Boolean, earlier: Boolean = false) =
            ProgressiveReplyProjection.rows(TimelineEventReducer.groups(items), running, if (running) "t" else null, earlier)
        val visible = rows(live, true)
        assertEquals(live.map { it.id }, visible.flatMap { it.group }.map { it.id })
        org.junit.Assert.assertFalse(visible.first { it.key == "p" }.showActions)
        org.junit.Assert.assertFalse(visible.first { it.key == "f" }.showActions)
        val ended = rows(live.dropLast(1) + final.copy(status = "completed"), false)
        assertEquals(listOf("u", "c", "p"), ended.map { it.key })
        assertEquals(listOf("c", "tool"), ended[1].group.map { it.id })
        org.junit.Assert.assertTrue(ended[1].forceActivity)
        assertEquals("第一部分\n\n第二部分", ended.last().group.single().text)
        assertEquals("第一部分\n\n第二部分", ended.last().actionItem?.text)
        assertEquals("f", ended.last().actionItem?.id)
        assertEquals("t", ended.last().actionItem?.turnId)
        val interrupted = rows(live.dropLast(1), false)
        assertEquals("第一部分\n\n", interrupted.last().actionItem?.text)
        assertEquals("已保留阶段性回答，未收到最终回答", interrupted.last().note)
        val clipped = rows(live.drop(1).dropLast(1) + final.copy(status = "completed"), false, true)
        org.junit.Assert.assertFalse(clipped.last().showActions)
        org.junit.Assert.assertNotNull(clipped.last().note)
        val steering = user.copy(id = "steer", text = "继续")
        val steered = rows(listOf(user, partial, steering, commentary, tool, final.copy(status = "completed")), false)
        assertEquals(listOf("u", "p", "steer", "c", "f"), steered.map { it.key })
        org.junit.Assert.assertFalse(steered[1].showActions)
        assertEquals("第一部分\n\n第二部分", steered.last().actionItem?.text)
        assertEquals(1, steered.count { it.actionItem != null && it.showActions })
        val unknown = final.copy(phase = "", status = "completed")
        assertEquals(unknown, rows(listOf(unknown), false).single().group.single())
        val other = final.copy(id = "other", turnId = "other-turn", status = "completed")
        val separate = rows(listOf(user, partial, other), false)
        assertEquals("第一部分\n\n", separate[1].actionItem?.text)
        assertEquals("第二部分", separate.last().group.single().text)
        val instruction = ProgressiveReplyProjection.appendInstruction("原有指令")
        org.junit.Assert.assertTrue(instruction.startsWith("原有指令\n\n"))
        assertEquals(instruction, ProgressiveReplyProjection.appendInstruction(instruction))
    }

    @Test fun streamingMarkdownNeverCommitsBlankLinesInsideOpenCode() {
        val opening = "标题\n\n" + "\u0060\u0060\u0060kt\n第一行\n\n第二行"
        assertEquals("标题\n\n".length, MessageMarkdown.stablePrefixLength(opening))
        val closed = opening + "\n\u0060\u0060\u0060\n\n"
        assertEquals(closed.length, MessageMarkdown.stablePrefixLength(closed))
        assertEquals(closed.length, MessageMarkdown.stablePrefixLength(closed + "未完"))
    }

    @Test fun nativePartialPhaseSurvivesDeltaCompletionAndHistoryRestore() {
        fun raw(id: String, phase: String, text: String) = org.json.JSONObject().put("type", "agentMessage")
            .put("id", id).put("phase", phase).put("text", text)
        fun params(item: org.json.JSONObject) = org.json.JSONObject().put("turnId", "turn").put("item", item)
        val partial = raw("p", "partial_answer", "")
        var timeline = TimelineEventReducer.reduce("item/started", params(partial), emptyList())
        val delta = org.json.JSONObject().put("turnId", "turn").put("itemId", "p").put("delta", "A\n\n")
        timeline = TimelineEventReducer.reduce("item/agentMessage/delta", delta, timeline)
        assertEquals("partial_answer", timeline.single().phase)
        assertEquals("A\n\n", timeline.single().text)
        partial.put("text", "A\n\n")
        timeline = TimelineEventReducer.reduce("item/completed", params(partial), timeline)
        val final = raw("f", "final_answer", "B")
        timeline = TimelineEventReducer.reduce("item/completed", params(final), timeline)
        val cache = ProgressiveReplyProjection.Cache()
        val groups = TimelineEventReducer.groups(timeline)
        val before = cache.rows(groups, false, null, false)
        assertEquals("A\n\nB", before.single().actionItem?.text)
        val next = cache.rows(groups + listOf(listOf(TimelineItem("next", "user", "新任务", "completed", turnId = "next"))),
            true, "next", false)
        org.junit.Assert.assertSame(before.single(), next.first())
        val saved = listOf(partial, final).map {
            TimelineEventReducer.historyEntry(params(it).put("completedAtMs", 1))
        }
        assertEquals(before, ProgressiveReplyProjection.rows(TimelineEventReducer.groups(saved), false, null, false))
    }
}
