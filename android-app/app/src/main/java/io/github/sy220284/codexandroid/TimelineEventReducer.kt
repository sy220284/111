package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Canonical item projection shared by live notifications and saved history. */
internal object TimelineEventReducer {
    val methods = setOf("item/started", "item/completed", "item/agentMessage/delta",
        "item/plan/delta", "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded",
        "item/commandExecution/outputDelta", "item/commandExecution/terminalInteraction",
        "item/fileChange/outputDelta", "item/fileChange/patchUpdated",
        "item/mcpToolCall/progress", "turn/plan/updated", "turn/completed")

    private fun JSONObject.string(key: String) = if (isNull(key)) "" else optString(key)
    private fun JSONArray?.objects() = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings() = if (this == null) emptyList() else
        (0 until length()).map { optString(it) }.filter(String::isNotBlank)
    private fun JSONObject.arrayText(key: String) = optJSONArray(key).strings().joinToString("\n\n")
    private fun shortPath(path: String) = path.substringAfterLast('/').ifBlank { "文件" }

    fun project(item: JSONObject, turnId: String, lifecycle: String): TimelineItem {
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
            "mcpToolCall", "dynamicToolCall" -> listOf(item.string("server"), item.string("tool")).filter(String::isNotBlank).joinToString(" · ")
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
            text, status, evidence.toString(), turnId, item.string("phase"))
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

    fun reduce(method: String, params: JSONObject, current: List<TimelineItem>): List<TimelineItem> {
        val turnId = params.string("turnId")
        val items = current.toMutableList()
        fun put(item: TimelineItem) {
            val clientId = if (item.kind == "user" && item.evidence.isNotBlank())
                JSONObject(item.evidence).string("clientId") else ""
            val i = TimelineReconciler.position(items, item.id, if (item.kind == "user") "userMessage" else item.kind, clientId)
            if (i < 0) items.add(item) else items[i] = item
        }
        if (method == "turn/completed") {
            val turn = params.getJSONObject("turn")
            val id = turn.getString("id")
            val status = turn.string("status")
            turn.optJSONArray("items").objects().forEach { put(project(it, id, "completed")) }
            return items.map {
                if (it.turnId == id && it.status in setOf("streaming", "inProgress", "running"))
                    it.copy(status = if (status == "completed") "completed" else status)
                else it
            }
        }
        if (method == "turn/plan/updated") {
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
            val old = items.find { it.id == id }
            if (method == "item/started" && old != null && old.status != "sending") return current
            val complete = method == "item/completed"
            val next = project(raw, turnId, if (complete) "completed" else "inProgress")
            // Completed payload is authoritative; retain streamed output only when omitted.
            val evidence = JSONObject(next.evidence)
            if (old != null && old.evidence.isNotBlank()) {
                val prior = JSONObject(old.evidence)
                for (key in listOf("aggregatedOutput", "output", "progress")) {
                    if (evidence.isNull(key) && !prior.isNull(key)) evidence.put(key, prior.get(key))
                }
            }
            put(next.copy(evidence = evidence.toString()))
            return items
        }
        val id = params.string("itemId")
        if (id.isBlank()) return current
        val kind = method.substringAfter("item/").substringBefore("/")
        val old = items.find { it.id == id }
        if (old != null && old.status in setOf("completed", "failed", "declined", "interrupted")) return current
        val raw = if (old?.evidence.isNullOrBlank()) JSONObject().put("id", id).put("type", kind)
            else JSONObject(old!!.evidence)
        val delta = params.string("delta")
        when (method) {
            "item/agentMessage/delta", "item/plan/delta" -> raw.put("text", raw.string("text") + delta)
            "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded" -> {
                val index = params.getInt("summaryIndex")
                val summary = raw.optJSONArray("summary") ?: JSONArray()
                while (summary.length() <= index) summary.put("")
                summary.put(index, summary.optString(index) + delta)
                raw.put("summary", summary)
            }
            "item/commandExecution/outputDelta" -> raw.put("aggregatedOutput", raw.string("aggregatedOutput") + delta)
            "item/fileChange/outputDelta" -> raw.put("output", raw.string("output") + delta)
            "item/fileChange/patchUpdated" -> raw.put("changes", params.getJSONArray("changes"))
            "item/mcpToolCall/progress" -> raw.put("progress", raw.string("progress") + "\n" + params.string("message"))
            "item/commandExecution/terminalInteraction" -> raw.put("terminalInteraction", params)
            else -> return current
        }
        put(project(raw, turnId, "streaming"))
        return items
    }

    /** A compact activity group is interrupted by any user, answer, plan or turn boundary. */
    fun groups(items: List<TimelineItem>): List<List<TimelineItem>> {
        val result = mutableListOf<MutableList<TimelineItem>>()
        val activity = setOf("reasoning", "commandExecution", "fileChange", "mcpToolCall",
            "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "sleep", "hookPrompt")
        for (item in items) {
            val last = result.lastOrNull()
            if (last != null && item.kind in activity && last.first().kind in activity &&
                item.turnId.isNotBlank() && item.turnId == last.first().turnId) last.add(item)
            else result.add(mutableListOf(item))
        }
        return result
    }
}
