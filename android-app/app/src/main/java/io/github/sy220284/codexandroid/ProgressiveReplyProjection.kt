package io.github.sy220284.codexandroid

/** Presentation only: original item IDs, phases and ordering remain owned by the core timeline. */
internal object ProgressiveReplyProjection {
    internal data class Row(
        val key: String,
        val group: List<TimelineItem>,
        val showActions: Boolean = true,
        val actionItem: TimelineItem? = null,
        val forceActivity: Boolean = false,
        val note: String? = null,
        val liveProcess: Boolean = false
    )

    private val assistantKinds = setOf("agent", "agentMessage")
    private val activityKinds = setOf("reasoning", "commandExecution", "fileChange", "mcpToolCall",
        "dynamicToolCall", "functionCallOutput", "webSearch", "imageView", "sleep", "hookPrompt")
    private val runningStates = setOf("inProgress", "in_progress", "running", "streaming", "sending")
    fun commentary(item: TimelineItem) = item.kind in assistantKinds && item.phase == "commentary"
    fun answer(item: TimelineItem) = item.kind in assistantKinds && !commentary(item)
    fun answerText(items: List<TimelineItem>): String =
        items.filter(::answer).joinToString("") { it.text }

    fun rows(groups: List<List<TimelineItem>>, running: Boolean, activeTurn: String?,
        hasEarlier: Boolean): List<Row> = Cache().rows(groups, running, activeTurn, hasEarlier)

    /** Reuse closed turn projections as native IndexedTimeline retains unaffected group instances. */
    internal class Cache {
        private data class Entry(val groups: List<List<TimelineItem>>, val running: Boolean,
            val earlier: Boolean, val rows: List<Row>)
        private var entries = emptyMap<String, Entry>()

        fun rows(groups: List<List<TimelineItem>>, running: Boolean, activeTurn: String?,
            hasEarlier: Boolean): List<Row> {
            val updated = mutableMapOf<String, Entry>()
            val result = buildList {
                var index = 0
                while (index < groups.size) {
                    val start = index
                    val turn = groups[index].first().turnId
                    index++
                    if (turn.isNotBlank()) {
                        while (index < groups.size && groups[index].first().turnId == turn) index++
                    }
                    val section = groups.subList(start, index)
                    val current = running && (turn == activeTurn || (activeTurn.isNullOrBlank() && index == groups.size))
                    val earlier = start == 0 && hasEarlier
                    val key = section.first().first().id
                    val cached = entries[key]?.takeIf { old ->
                        old.running == current && old.earlier == earlier &&
                            old.groups.size == section.size && section.indices.all { old.groups[it] === section[it] }
                    }
                    val entry = cached ?: Entry(section.toList(), current, earlier, sectionRows(section, current, earlier))
                    updated[key] = entry
                    addAll(entry.rows)
                }
            }
            entries = updated
            return result
        }
    }

    private fun sectionRows(section: List<List<TimelineItem>>, running: Boolean,
        hasEarlier: Boolean): List<Row> = buildList {
        val items = section.flatten()
        val turn = items.first().turnId
        val answers = items.filter(::answer)
        val progressive = answers.any { it.phase == "partial_answer" }
        val explicit = answers.any { it.phase in setOf("partial_answer", "final_answer") }
        val open = running || items.any { it.status in runningStates }
        // Item paging can start inside a turn, even at a steering user message. Never claim a
        // complete answer until the preceding page has established the turn's beginning.
        val clipped = hasEarlier && turn.isNotBlank() && explicit
        val incompleteNote = "加载更早记录后可使用完整回答操作"
        if (turn.isBlank() || open || (!progressive && items.none(::commentary))) {
            section.forEach { group ->
                val single = group.singleOrNull()
                val formal = single?.let(::answer) == true
                val allow = !formal || (!open && !clipped && single?.phase != "partial_answer")
                add(Row(group.first().id, group, showActions = allow,
                    actionItem = single?.takeIf { formal && clipped && !open },
                    note = if (formal && clipped && !open) incompleteNote else null,
                    liveProcess = open && group.all { it.kind in activityKinds }))
            }
            return@buildList
        }

        // Keep user steering, plans, errors, generated media and other special items at their
        // original boundaries. Only ordinary execution records are gathered ahead of text.
        val output = mutableListOf<Row>()
        var segment = mutableListOf<TimelineItem>()
        fun flush() {
            if (segment.isEmpty()) return
            val process = segment.filterNot(::answer)
            val formal = segment.filter(::answer)
            if (process.isNotEmpty()) output += Row(process.first().id, process, forceActivity = true)
            if (progressive && formal.isNotEmpty()) {
                val joined = formal.last().copy(text = answerText(formal), evidence = "")
                output += Row(formal.first().id, listOf(joined), showActions = false)
            } else formal.forEach { output += Row(it.id, listOf(it), showActions = !clipped,
                actionItem = it.takeIf { clipped }, note = incompleteNote.takeIf { clipped }) }
            segment = mutableListOf()
        }
        items.forEach { item ->
            if (answer(item) || commentary(item) || item.kind in activityKinds) segment.add(item)
            else { flush(); output += Row(item.id, listOf(item)) }
        }
        flush()
        if (progressive && answers.isNotEmpty()) {
            val last = output.indexOfLast { it.group.singleOrNull()?.let(::answer) == true && !it.forceActivity }
            val aggregate = answers.last().copy(text = answerText(answers), evidence = "")
            val note = when {
                clipped -> incompleteNote
                answers.none { it.phase == "final_answer" || it.phase.isBlank() } -> "已保留阶段性回答，未收到最终回答"
                else -> null
            }
            if (last >= 0) output[last] = output[last].copy(showActions = !clipped,
                actionItem = aggregate, note = note)
        }
        addAll(output)
    }

    const val instruction = "长任务采用渐进式回复：开始工作时简短说明计划；获得可靠且可独立使用的结果后，及时输出正式答案片段并继续执行；过程更新与正式答案分开，最终回答补齐剩余结果，避免重复已交付内容。只有任务完成或需要用户决策时结束当前回合。"
    fun appendInstruction(current: String): String =
        if (current.contains(instruction)) current else if (current.isBlank()) instruction else current.trimEnd() + "\n\n" + instruction
}
