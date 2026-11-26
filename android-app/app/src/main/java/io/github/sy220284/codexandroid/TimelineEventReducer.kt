package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Canonical item projection shared by live notifications and saved history. */
internal object TimelineEventReducer {
    val methods = setOf("item/started", "item/completed", "item/agentMessage/delta",
        "item/plan/delta", "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded",
        "item/commandExecution/outputDelta", "item/commandExecution/terminalInteraction",
        "item/fileChange/outputDelta", "item/fileChange/patchUpdated",
        "item/mcpToolCall/progress", "hook/started", "hook/completed", "turn/plan/updated", "turn/completed")

    private val runningStates = setOf("streaming", "inProgress", "in_progress", "running", "sending")
    private val terminalStates = setOf("completed", "failed", "blocked", "stopped", "declined", "interrupted", "cancelled", "incomplete", "unverified")

    private fun JSONObject.string(key: String) = if (isNull(key)) "" else optString(key)
    private fun JSONArray?.objects() = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings() = if (this == null) emptyList() else
        (0 until length()).map { optString(it) }.filter(String::isNotBlank)
    private fun JSONObject.arrayText(key: String) = optJSONArray(key).strings().joinToString("\n\n")
    private fun shortPath(path: String) = path.substringAfterLast('/').ifBlank { "文件" }

    fun project(rawItem: JSONObject, turnId: String, lifecycle: String): TimelineItem {
        val item = AgentReplyContract.sanitize(rawItem)
        val kind = item.string("type")
        val text = when (kind) {
            "userMessage" -> item.optJSONArray("content").objects().mapNotNull {
                when (it.string("type")) {
                    "text" -> it.string("text")
                    "image", "localImage" -> "［图片］"
                    "audio", "localAudio" -> "［音频］"
                    "skill", "mention" -> it.string("name").takeIf(String::isNotBlank)?.let { name -> "@" + name }
                    else -> null
                }
            }.joinToString("\n")
            "agentMessage", "plan" -> item.string("text")
            // Only the model's user-facing summary is presented; raw reasoning is not rendered.
            "reasoning" -> item.arrayText("summary")
            "commandExecution" -> {
                val actions = item.optJSONArray("commandActions").objects()
                val descriptions = actions.mapNotNull {
                    when (it.string("type")) {
                        "read" -> "读取 " + it.string("name").ifBlank { shortPath(it.string("path")) }
                        "listFiles" -> "浏览 " + shortPath(it.string("path"))
                        "search" -> "搜索 " + it.string("query").ifBlank { shortPath(it.string("path")) }
                        else -> null
                    }
                }
                if (descriptions.isNotEmpty() && descriptions.size == actions.size) descriptions.joinToString(" · ")
                else "运行命令"
            }
            "fileChange" -> {
                val changes = item.optJSONArray("changes").objects()
                "修改 ${changes.size} 个文件" + changes.take(3).joinToString("", prefix = if (changes.isEmpty()) "" else " · ") { shortPath(it.string("path")) + " " }.trimEnd()
            }
            "mcpToolCall", "dynamicToolCall" -> if (item.string("server") == ModelPreviewMcpServer.NAME &&
                item.string("tool") == ModelPreviewMcpServer.TOOL) "准备任务文件预览"
                else listOf(item.string("server"), item.string("tool")).filter(String::isNotBlank).joinToString(" · ")
            "functionCallOutput" -> item.string("name")
            "webSearch" -> item.string("query")
            "imageView" -> shortPath(item.string("path"))
            "imageGeneration" -> item.string("revisedPrompt").ifBlank { "生成图片" }
            "collabAgentToolCall" -> item.string("tool")
            "subAgentActivity" -> item.string("agentPath")
            "sleep" -> "等待 ${item.optLong("durationMs") / 1000} 秒"
            "enteredReviewMode", "exitedReviewMode" -> item.string("review")
            "contextCompaction" -> if (lifecycle == "completed") "上下文已整理" else "正在整理上下文"
            "hookPrompt" -> "任务附加提示"
            else -> item.string("text")
        }
        val status = item.string("status").ifBlank { lifecycle }
        // Do not expose private/raw reasoning content through the evidence reader.
        val evidence = JSONObject(item.toString()).apply { if (kind == "reasoning") remove("content") }
        return TimelineItem(item.getString("id"), if (kind == "userMessage") "user" else kind,
            text, status, evidence.toString(), turnId, if (kind == "agentMessage") item.string("phase") else "")
    }

    fun historyTurns(items: List<TimelineItem>): JSONArray = JSONArray().apply {
        items.filter { it.turnId.isNotBlank() }.groupBy { it.turnId }.forEach { (id, group) ->
            val records = JSONArray()
            group.filter { it.kind == "fileChange" && it.evidence.isNotBlank() }.forEach {
                records.put(JSONObject(it.evidence))
            }
            put(JSONObject().put("id", id).put("items", records))
        }
    }

    fun historyEntry(entry: JSONObject): TimelineItem {
        val item = entry.getJSONObject("item")
        val completed = !entry.isNull("completedAtMs")
        // Persisted older items may have no timing metadata; status-bearing types retain their own state.
        val lifecycle = if (completed || entry.isNull("startedAtMs")) "completed" else "inProgress"
        return project(item, entry.getString("turnId"), lifecycle)
    }

    fun outcomeId(turnId: String) = "turn-completed:$turnId"
    fun terminalOutcome(items: List<TimelineItem>, turnId: String): String? = items.lastOrNull {
        it.id == outcomeId(turnId) || it.id == "turn-error:$turnId"
    }?.status

    fun reduce(method: String, params: JSONObject, current: List<TimelineItem>): List<TimelineItem> {
        val turnId = params.string("turnId")
        var items = IndexedTimeline.from(current)
        val closed = items.byId(outcomeId(turnId)) != null || items.byId("turn-error:$turnId") != null
        fun put(item: TimelineItem) {
            // All lifecycle paths, including turn/completed backfill, preserve an already known
            // native phase when a later payload omits that optional field.
            val prior = items.byId(item.id)
            val phase = if (item.kind == "agentMessage") item.phase.ifBlank { prior?.phase.orEmpty() } else item.phase
            val next = if (phase == item.phase) item else item.copy(phase = phase,
                evidence = JSONObject(item.evidence).put("phase", phase).toString())
            val clientId = if (next.kind == "user" && next.evidence.isNotBlank())
                JSONObject(next.evidence).string("clientId") else ""
            items = items.put(next, clientId)
        }
        if (method == "hook/started" || method == "hook/completed") {
            if (closed) return current
            val hook = HookRunPresentation.project(params) ?: return current
            val prior = items.byId(hook.id)
            if (prior != null && prior.status != "running" && hook.status == "running") return current
            put(hook)
            return items
        }
        if (method == "turn/completed") {
            val turn = params.getJSONObject("turn")
            val id = turn.getString("id")
            val status = turn.string("status")
            if (status !in setOf("completed", "failed", "interrupted")) return current
            turn.optJSONArray("items").objects().forEach { raw ->
                val item = project(raw, id, if (raw.string("type") == "userMessage") "completed" else status)
                val prior = items.byId(item.id)
                if (prior == null || prior.status !in terminalStates || prior.status == "unverified") {
                    // A turn snapshot may contain an unfinished message with no explicit item status.
                    // On failure preserve the text actually received and never promote it to success.
                    val evidence = JSONObject(item.evidence)
                    if (prior?.hasEvidence == true) {
                        val previous = JSONObject(prior.evidence)
                        for (key in listOf("aggregatedOutput", "output", "progress")) {
                            if (evidence.isNull(key) && !previous.isNull(key)) evidence.put(key, previous.get(key))
                        }
                    }
                    val retainedText = if (item.kind == "agentMessage" && prior != null &&
                        (raw.isNull("text") || (status != "completed" && prior.text.startsWith(item.text))))
                        prior.text else item.text
                    if (item.kind == "agentMessage") evidence.put("text", retainedText)
                    put(AgentReplyContract.merge(prior, item.copy(text = retainedText, evidence = evidence.toString())))
                } else if (item.kind == "agentMessage" && prior.kind == item.kind) {
                    // Keep completed text, but accept authoritative attributes, including restrictions.
                    put(AgentReplyContract.merge(prior, item.copy(status = prior.status), keepText = true))
                }
            }
            if (status == "completed") put(TimelineItem(outcomeId(id), "turnOutcome", "", status, turnId = id))
            else put(TimelineItem("turn-error:$id", "error",
                turn.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
                    if (status == "interrupted") "任务已中断，已保留收到的内容" else "任务执行失败，已保留收到的内容"
                }, status, turnId = id))
            return IndexedTimeline.from(items.filterNot { it.id == "turn-unverified:$id" ||
                (status == "completed" && it.id == "turn-error:$id") ||
                (status != "completed" && it.id == outcomeId(id)) }.map {
                if (it.turnId == id && (it.status in runningStates || it.status == "unverified"))
                    it.withStatus(if (it.kind == "user") "completed" else status) else it
            })
        }
        if (method == "turn/plan/updated") {
            if (closed) return current
            val plan = params.optJSONArray("plan").objects().joinToString("\n") {
                val symbol = when (it.string("status")) { "completed" -> "✓"; "inProgress" -> "•"; else -> "○" }
                symbol + " " + it.string("step")
            }
            put(TimelineItem("plan-progress:$turnId", "planProgress", plan, "inProgress", params.toString(), turnId))
            return items
        }
        if (method == "item/started" || method == "item/completed") {
            val raw = params.getJSONObject("item")
            val id = raw.getString("id")
            val old = items.byId(id)
            val complete = method == "item/completed"
            val next = project(raw, turnId, if (complete) "completed" else "inProgress")
            val failedTurn = items.byId("turn-error:$turnId") != null
            if (closed && old == null && !complete) return current
            if (old?.status in terminalStates && !complete && next.kind != "agentMessage") return current
            if (!complete && old != null && old.status != "sending") {
                // A resumed subscription may have received text before its lifecycle metadata.
                // Enrich the same native item without resetting its accumulated text.
                if (old.kind == "agentMessage" && next.kind == old.kind) {
                    put(AgentReplyContract.merge(old, next.copy(status = old.status), keepText = true))
                    return items
                }
                return current
            }
            // Completed payload is authoritative; retain streamed output only when omitted.
            val evidence = JSONObject(next.evidence)
            if (old != null && old.evidence.isNotBlank()) {
                val prior = JSONObject(old.evidence)
                for (key in listOf("aggregatedOutput", "output", "progress")) {
                    if (evidence.isNull(key) && !prior.isNull(key)) evidence.put(key, prior.get(key))
                }
            }
            val terminal = if (failedTurn && old != null && old.status in terminalStates) old.status else next.status
            val retained = if (failedTurn && old != null && next.kind == "agentMessage" &&
                (raw.isNull("text") || old.text.startsWith(next.text))) old.text else next.text
            if (next.kind == "agentMessage") evidence.put("text", retained)
            put(AgentReplyContract.merge(old, next.copy(text = retained, status = terminal, evidence = evidence.toString())))
            return items
        }
        val id = params.string("itemId")
        if (id.isBlank()) return current
        val kind = method.substringAfter("item/").substringBefore("/")
        val old = items.byId(id)
        if (closed || (old != null && old.status in terminalStates)) return current
        if (kind == "agentMessage" && AgentReplyContract.restricted(old?.metadata())) return current
        val delta = params.string("delta")
        val field = when (method) {
            "item/agentMessage/delta", "item/plan/delta" -> "text"
            "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded" -> "summary"
            "item/commandExecution/outputDelta" -> "aggregatedOutput"
            "item/fileChange/outputDelta" -> "output"
            "item/mcpToolCall/progress" -> "progress"
            else -> null
        }
        if (field != null) {
            val initial = old?.takeIf { it.hasEvidence }
                ?: project(JSONObject().put("id", id).put("type", kind), turnId, "streaming")
            val index = if (field == "summary") params.getInt("summaryIndex") else 0
            val addition = if (field == "progress") "\n" + params.string("message") else delta
            put(initial.appendNative(field, addition, index, "streaming"))
            return items
        }
        val raw = if (old?.hasEvidence != true) JSONObject().put("id", id).put("type", kind)
            else JSONObject(old.evidence)
        when (method) {
            "item/fileChange/patchUpdated" -> raw.put("changes", params.getJSONArray("changes"))
            "item/commandExecution/terminalInteraction" -> raw.put("terminalInteraction", params)
            else -> return current
        }
        put(project(raw, turnId, "streaming"))
        return items
    }

    /** Losing the connection establishes neither a successful nor a failed native turn. */
    fun connectionLost(current: List<TimelineItem>, turnId: String): List<TimelineItem> {
        if (turnId.isBlank()) return current
        val items = IndexedTimeline.from(current.map {
            if (it.turnId == turnId && it.status in runningStates) it.withStatus("unverified") else it
        })
        return items.put(TimelineItem("turn-unverified:$turnId", "error",
            "运行核心已断开，任务结果待核实。已保留收到的内容，请重新连接后查看。",
            "unverified", turnId = turnId))
    }

    /** A compact activity group is interrupted by any user, answer, plan or turn boundary. */
    fun groups(items: List<TimelineItem>): List<List<TimelineItem>> =
        if (items is IndexedTimeline) items.grouping.groups else TimelineGrouping.from(items).groups
}
