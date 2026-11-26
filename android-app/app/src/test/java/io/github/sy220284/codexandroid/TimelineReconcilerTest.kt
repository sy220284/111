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
        val oldEpoch = TimelineDeltaBatch(delta("answer", "old").put(CORE_SESSION_FIELD, 1L))
        org.junit.Assert.assertFalse(oldEpoch.append(delta("answer", "new").put(CORE_SESSION_FIELD, 2L)))
        assertEquals(1L, oldEpoch.notification().wire.getLong(CORE_SESSION_FIELD))
        assertEquals("ab", batch.notification().delta)
        assertEquals("a", first.getJSONObject("params").getString("delta"))
        val sequential = TimelineEventReducer.reduce("item/agentMessage/delta", second.getJSONObject("params"),
            TimelineEventReducer.reduce("item/agentMessage/delta", first.getJSONObject("params"), emptyList()))
        assertEquals(sequential, TimelineEventReducer.reduce("item/agentMessage/delta",
            batch.notification().params, emptyList(), batch.notification().mergedDelta))
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
        org.junit.Assert.assertTrue(visible.first { it.key == "tool" }.liveProcess)
        org.junit.Assert.assertFalse(visible.first { it.key == "c" }.liveProcess)
        val ended = rows(live.dropLast(1) + final.copy(status = "completed"), false)
        assertEquals(listOf("u", "c", "p", "f"), ended.map { it.key })
        assertEquals(listOf("c", "tool"), ended[1].group.map { it.id })
        org.junit.Assert.assertTrue(ended[1].forceActivity)
        org.junit.Assert.assertFalse(ended[1].liveProcess)
        assertEquals("第二部分", ended.last().group.single().text)
        assertEquals("第一部分\n\n第二部分", ended.last().actionItem?.text)
        assertEquals("f", ended.last().actionItem?.id)
        assertEquals("t", ended.last().actionItem?.turnId)
        val interrupted = rows(live.dropLast(1), false)
        assertEquals("第一部分\n\n", interrupted.last().actionItem?.text)
        assertEquals("已保留阶段性回答，未收到确认的最终回答", interrupted.last().note)
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
        val oldInstruction = instruction.substringBeforeLast("\n\n")
        val migrated = ProgressiveReplyProjection.appendInstruction(oldInstruction)
        assertEquals(instruction, migrated)
        assertEquals(migrated, ProgressiveReplyProjection.appendInstruction(migrated))
    }

    @Test fun richReplyBlocksRequireCompleteValidatedFences() {
        val fence = "```"
        val metrics = fence + "codex-metrics\n文件数|11,404|已检查\n开放 PR|3\n" + fence
        val block = MessageMarkdown.blocks(metrics).single()
        assertEquals("metrics", block.kind)
        assertEquals(listOf("文件数", "11,404", "已检查"), block.rows.first())
        assertEquals(metrics, block.source)
        val spaced = fence + " codex-metrics  \n文件数|3\n" + fence
        assertEquals(spaced, MessageMarkdown.blocks(spaced).single().source)
        assertEquals("code", MessageMarkdown.blocks(metrics.dropLast(3)).single().kind)
        assertEquals("code", MessageMarkdown.blocks(fence + "codex-metrics\n错误行\n" + fence).single().kind)
        val flow = MessageMarkdown.blocks(fence + "codex-flow\n读取源码\n执行检查\n" + fence).single()
        assertEquals("flow", flow.kind)
        assertEquals(listOf(listOf("读取源码"), listOf("执行检查")), flow.rows)
        org.junit.Assert.assertNull(RichReplyContent.parse("codex-flow", List(13) { "步骤" }.joinToString("\n")))
        org.junit.Assert.assertNull(RichReplyContent.parse("codex-metrics", "标签||说明"))
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
        assertEquals(listOf("p", "f"), before.map { it.key })
        assertEquals("A\n\nB", before.last().actionItem?.text)
        val next = cache.rows(groups + listOf(listOf(TimelineItem("next", "user", "新任务", "completed", turnId = "next"))),
            true, "next", false)
        before.indices.forEach { org.junit.Assert.assertSame(before[it], next[it]) }
        val saved = listOf(partial, final).map {
            TimelineEventReducer.historyEntry(params(it).put("completedAtMs", 1))
        }
        assertEquals(before, ProgressiveReplyProjection.rows(TimelineEventReducer.groups(saved), false, null, false))
    }

    @Test fun terminalTurnPreservesLatePhaseAndNeverPromotesInterruptedText() {
        fun message(id: String, text: String, phase: String? = null) =
            org.json.JSONObject().put("id", id).put("type", "agentMessage").put("text", text)
                .apply { if (phase != null) put("phase", phase) }
        fun params(raw: org.json.JSONObject) = org.json.JSONObject().put("turnId", "t").put("item", raw)
        fun ended(status: String, vararg raw: org.json.JSONObject) = org.json.JSONObject()
            .put("turn", org.json.JSONObject().put("id", "t").put("status", status)
                .put("items", org.json.JSONArray(raw.toList())))
        val prior = TimelineEventReducer.reduce("item/completed", params(message("a", "已确认")), emptyList())
        val phase = TimelineEventReducer.reduce("turn/completed",
            ended("completed", message("a", "已确认", "commentary")), prior)
        assertEquals("commentary", phase.first().phase)
        assertEquals("已确认", phase.first().text)
        val stream = TimelineEventReducer.reduce("item/started", params(message("f", "", "final_answer")), emptyList())
        val delta = org.json.JSONObject().put("turnId", "t").put("itemId", "f").put("delta", "尚未完成")
        val pending = TimelineEventReducer.reduce("item/agentMessage/delta", delta, stream)
        val terminal = TimelineEventReducer.reduce("turn/completed",
            ended("interrupted", message("f", "", "final_answer")), pending)
        assertEquals("尚未完成", terminal.first().text)
        assertEquals("interrupted", terminal.first().status)
        assertEquals("interrupted", terminal.last().status)
        val rows = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(terminal), false, null, false)
        assertEquals("任务未成功完成，已保留收到的回答", rows.first().note)
        assertEquals(terminal, TimelineEventReducer.reduce("item/agentMessage/delta", delta, terminal))
        val late = TimelineEventReducer.reduce("item/completed", params(message("f", "", "final_answer")), terminal)
        assertEquals("尚未完成", late.first().text)
        assertEquals("interrupted", late.first().status)
        assertEquals(pending, TimelineEventReducer.reduce("turn/completed", ended("inProgress"), pending))
        val completed = TimelineEventReducer.reduce("turn/completed",
            ended("completed", message("f", "完整正文", "final_answer")), pending)
        assertEquals("completed", completed.first().status)
        assertEquals("完整正文", completed.first().text)
        assertEquals("turn-completed:t", completed.last().id)
        assertEquals(completed, TimelineEventReducer.reduce("item/agentMessage/delta", delta.put("itemId", "late"), completed))
        assertEquals("commentary", ConversationLiveTimeline.merge(prior, phase).first().phase)
        val updated = ConversationLiveTimeline.merge(listOf(prior.single().copy(phase = "partial_answer")), phase)
        assertEquals("commentary", updated.first().phase)
    }

    @Test fun disconnectRetainsTextWithoutInventingNativeTurnOutcome() {
        val item = TimelineItem("f", "agentMessage", "收到的内容", "streaming", turnId = "t", phase = "final_answer")
        val disconnected = TimelineEventReducer.connectionLost(listOf(item), "t")
        assertEquals("unverified", disconnected.first().status)
        assertEquals("收到的内容", disconnected.first().text)
        assertEquals("turn-unverified:t", disconnected.last().id)
        val rows = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(disconnected), false, null, false)
        assertEquals("连接中断，任务结果待核实", rows.first().note)
        val native = org.json.JSONObject().put("id", "t").put("status", "completed")
        val ended = TimelineEventReducer.reduce("turn/completed", org.json.JSONObject().put("turn", native), disconnected)
        assertEquals("completed", ended.first().status)
        assertEquals("收到的内容", ended.first().text)
        val commentary = item.copy(id = "c", phase = "commentary", status = "completed")
        val onlyProcess = ProgressiveReplyProjection.rows(TimelineEventReducer.groups(listOf(commentary)), false, null, false)
        assertEquals("已保留过程回复，未收到正式回答", onlyProcess.single().note)
        val saved = item.copy(status = "completed", phase = "final_answer")
        val stale = item.copy(phase = "partial_answer")
        assertEquals("final_answer", ConversationLiveTimeline.merge(listOf(saved), listOf(stale)).single().phase)
    }

    @Test fun latePhaseAndOffscreenResumeKeepAccumulatedNativeText() {
        val delta = org.json.JSONObject().put("turnId", "t").put("itemId", "a").put("delta", "A")
        val streamed = TimelineEventReducer.reduce("item/agentMessage/delta", delta, emptyList())
        assertEquals("", streamed.single().phase)
        val started = org.json.JSONObject().put("id", "a").put("type", "agentMessage")
            .put("phase", "partial_answer").put("text", "")
        val params = org.json.JSONObject().put("turnId", "t").put("item", started)
        val enriched = TimelineEventReducer.reduce("item/started", params, streamed)
        assertEquals("A", enriched.single().text)
        assertEquals("partial_answer", enriched.single().phase)
        val continued = TimelineEventReducer.reduce("item/agentMessage/delta", delta.put("delta", "B"), enriched)
        assertEquals("AB", continued.single().text)
        assertEquals("partial_answer", continued.single().phase)
        val done = org.json.JSONObject(started.toString()).apply { remove("phase"); put("text", "AB") }
        val completed = TimelineEventReducer.reduce("item/completed", params.put("item", done), continued)
        assertEquals("partial_answer", completed.single().phase)
        val turn = org.json.JSONObject().put("id", "t").put("status", "completed")
            .put("items", org.json.JSONArray().put(done))
        val backfilled = TimelineEventReducer.reduce("turn/completed", org.json.JSONObject().put("turn", turn), continued)
        assertEquals("partial_answer", backfilled.first().phase)
        assertEquals("AB", backfilled.first().text)
        val restored = ConversationLiveTimeline.merge(enriched, listOf(continued.single().copy(phase = "")))
        assertEquals("AB", restored.single().text)
        assertEquals("partial_answer", restored.single().phase)
    }
}
